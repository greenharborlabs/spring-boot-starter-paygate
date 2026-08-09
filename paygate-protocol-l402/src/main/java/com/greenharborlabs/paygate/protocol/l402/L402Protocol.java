package com.greenharborlabs.paygate.protocol.l402;

import com.greenharborlabs.paygate.api.ChallengeContext;
import com.greenharborlabs.paygate.api.ChallengeResponse;
import com.greenharborlabs.paygate.api.PaymentCredential;
import com.greenharborlabs.paygate.api.PaymentProtocol;
import com.greenharborlabs.paygate.api.PaymentValidationException;
import com.greenharborlabs.paygate.core.macaroon.Caveat;
import com.greenharborlabs.paygate.core.macaroon.KeyMaterial;
import com.greenharborlabs.paygate.core.macaroon.L402VerificationContext;
import com.greenharborlabs.paygate.core.macaroon.Macaroon;
import com.greenharborlabs.paygate.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.paygate.core.macaroon.MacaroonMinter;
import com.greenharborlabs.paygate.core.macaroon.MacaroonSerializer;
import com.greenharborlabs.paygate.core.macaroon.VerificationContextKeys;
import com.greenharborlabs.paygate.core.protocol.ErrorCode;
import com.greenharborlabs.paygate.core.protocol.L402Challenge;
import com.greenharborlabs.paygate.core.protocol.L402Credential;
import com.greenharborlabs.paygate.core.protocol.L402Exception;
import com.greenharborlabs.paygate.core.protocol.L402Validator;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * L402 protocol adapter that implements the protocol-agnostic {@link PaymentProtocol} interface by
 * delegating to the existing paygate-core L402 infrastructure.
 */
public class L402Protocol implements PaymentProtocol {

  private static final String SCHEME = "L402";
  private static final int MACAROON_IDENTIFIER_VERSION = 1;

  private final L402Validator validator;
  private final String serviceName;
  private final Clock clock;

  public L402Protocol(L402Validator validator, String serviceName) {
    this(validator, serviceName, Clock.systemUTC());
  }

  public L402Protocol(L402Validator validator, String serviceName, Clock clock) {
    this.validator = Objects.requireNonNull(validator, "validator must not be null");
    this.serviceName = Objects.requireNonNull(serviceName, "serviceName must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  @Override
  public String scheme() {
    return SCHEME;
  }

  private static final String L402_PREFIX = "L402 ";
  private static final String LSAT_PREFIX = "LSAT ";

  @Override
  public boolean canHandle(String authorizationHeader) {
    if (authorizationHeader == null || authorizationHeader.length() < 5) {
      return false;
    }
    return authorizationHeader.regionMatches(true, 0, L402_PREFIX, 0, L402_PREFIX.length())
        || authorizationHeader.regionMatches(true, 0, LSAT_PREFIX, 0, LSAT_PREFIX.length());
  }

  @Override
  public PaymentCredential parseCredential(String authorizationHeader)
      throws PaymentValidationException {
    try {
      L402Credential credential = L402Credential.parse(authorizationHeader);
      try {
        MacaroonIdentifier macId = MacaroonIdentifier.decode(credential.macaroon().identifier());
        byte[] preimage = credential.preimage().value();
        try {
          return new PaymentCredential(
              macId.paymentHash(),
              preimage,
              credential.tokenId(),
              SCHEME,
              null,
              new L402Metadata(
                  credential.macaroon(), credential.additionalMacaroons(), authorizationHeader));
        } finally {
          KeyMaterial.zeroize(preimage);
        }
      } finally {
        credential.destroy();
      }
    } catch (L402Exception e) {
      throw mapL402Exception(e);
    }
  }

  @Override
  public ChallengeResponse formatChallenge(ChallengeContext context) {
    Objects.requireNonNull(context, "context must not be null");
    String routePattern = requireChallengeBoundary(context.routePattern(), "route pattern");
    String requestMethod = requireChallengeBoundary(context.requestMethod(), "request method");

    byte[] tokenIdBytes = HexFormat.of().parseHex(context.tokenId());
    MacaroonIdentifier identifier =
        new MacaroonIdentifier(MACAROON_IDENTIFIER_VERSION, context.paymentHash(), tokenIdBytes);

    List<Caveat> caveats = new ArrayList<>();
    caveats.add(new Caveat("services", serviceName + ":0"));
    caveats.add(new Caveat("route", routePattern));
    caveats.add(new Caveat("method", requestMethod));
    String capability = context.capability();
    String capabilityCeiling = capability == null || capability.isBlank() ? "~" : capability;
    caveats.add(new Caveat(serviceName + "_capabilities", capabilityCeiling));
    Instant validUntil = Instant.now(clock).plusSeconds(context.timeoutSeconds());
    caveats.add(
        new Caveat(serviceName + "_valid_until", String.valueOf(validUntil.getEpochSecond())));

    byte[] rootKeyBytes = null;
    Macaroon macaroon;
    rootKeyBytes = context.rootKeyBytes();
    try {
      macaroon = MacaroonMinter.mint(rootKeyBytes, identifier, null, caveats);
    } finally {
      KeyMaterial.zeroize(rootKeyBytes);
    }

    byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
    String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);

    String safeBolt11 = L402Challenge.sanitizeBolt11ForHeader(context.bolt11Invoice());
    String wwwAuth =
        "L402 version=\"0\", token=\""
            + macaroonBase64
            + "\", macaroon=\""
            + macaroonBase64
            + "\", invoice=\""
            + safeBolt11
            + "\"";

    return new ChallengeResponse(wwwAuth, SCHEME, null);
  }

  /**
   * Validates an L402 credential for a trusted request boundary.
   *
   * @param credential the parsed L402 credential
   * @param requestContext request metadata containing non-blank {@link
   *     VerificationContextKeys#REQUEST_ROUTE} and {@link VerificationContextKeys#REQUEST_METHOD}
   * @throws PaymentValidationException when validation fails
   */
  @Override
  public void validate(PaymentCredential credential, Map<String, String> requestContext)
      throws PaymentValidationException {
    Objects.requireNonNull(credential, "credential must not be null");
    Objects.requireNonNull(requestContext, "requestContext must not be null");

    if (!(credential.metadata() instanceof L402Metadata metadata)) {
      throw new PaymentValidationException(
          PaymentValidationException.ErrorCode.MALFORMED,
          "Expected L402Metadata but got " + credential.metadata().getClass().getName(),
          credential.tokenId());
    }

    L402VerificationContext context =
        L402VerificationContext.builder()
            .serviceName(serviceName)
            .currentTime(Instant.now(clock))
            .requestMetadata(requestContext)
            .build();

    L402Validator.ValidationResult result = null;
    try {
      result = validator.validate(metadata.rawAuthorizationHeader(), context);
    } catch (L402Exception e) {
      throw mapL402Exception(e);
    } finally {
      if (result != null && result.credential() != null) {
        result.credential().destroy();
      }
    }
  }

  private static String requireChallengeBoundary(String value, String boundaryName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(boundaryName + " must not be blank");
    }
    return value;
  }

  /**
   * Maps an L402 core {@link ErrorCode} to the protocol-agnostic {@link
   * PaymentValidationException.ErrorCode}.
   */
  private static PaymentValidationException mapL402Exception(L402Exception e) {
    PaymentValidationException.ErrorCode mapped =
        switch (e.getErrorCode()) {
          case MALFORMED_HEADER -> PaymentValidationException.ErrorCode.MALFORMED;
          case INVALID_PREIMAGE, EXPIRED_CREDENTIAL, MISSING_REQUEST_CONTEXT ->
              PaymentValidationException.ErrorCode.INVALID;
          case INVALID_MACAROON, INVALID_SERVICE, REVOKED_CREDENTIAL ->
              PaymentValidationException.ErrorCode.INVALID;
          case LIGHTNING_UNAVAILABLE -> PaymentValidationException.ErrorCode.UNAVAILABLE;
        };
    return new PaymentValidationException(
        mapped, safeFailureMessage(e.getErrorCode()), e.getTokenId());
  }

  private static String safeFailureMessage(ErrorCode errorCode) {
    return switch (errorCode) {
      case MALFORMED_HEADER -> "Malformed L402 credential";
      case INVALID_PREIMAGE -> "L402 credential proof is invalid";
      case EXPIRED_CREDENTIAL -> "L402 credential has expired";
      case MISSING_REQUEST_CONTEXT -> "Request route and method context are required";
      case INVALID_MACAROON, INVALID_SERVICE, REVOKED_CREDENTIAL ->
          "L402 credential validation failed";
      case LIGHTNING_UNAVAILABLE -> "Lightning validation service is unavailable";
    };
  }
}
