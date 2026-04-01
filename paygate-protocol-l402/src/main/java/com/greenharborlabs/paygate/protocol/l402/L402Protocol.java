package com.greenharborlabs.paygate.protocol.l402;

import com.greenharborlabs.paygate.api.ChallengeContext;
import com.greenharborlabs.paygate.api.ChallengeResponse;
import com.greenharborlabs.paygate.api.PaymentCredential;
import com.greenharborlabs.paygate.api.PaymentProtocol;
import com.greenharborlabs.paygate.api.PaymentValidationException;
import com.greenharborlabs.paygate.core.macaroon.Caveat;
import com.greenharborlabs.paygate.core.macaroon.L402VerificationContext;
import com.greenharborlabs.paygate.core.macaroon.Macaroon;
import com.greenharborlabs.paygate.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.paygate.core.macaroon.MacaroonMinter;
import com.greenharborlabs.paygate.core.macaroon.MacaroonSerializer;
import com.greenharborlabs.paygate.core.protocol.ErrorCode;
import com.greenharborlabs.paygate.core.protocol.L402Credential;
import com.greenharborlabs.paygate.core.protocol.L402Exception;
import com.greenharborlabs.paygate.core.protocol.L402Validator;
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
  private static final int MACAROON_IDENTIFIER_VERSION = 0;

  private final L402Validator validator;
  private final String serviceName;

  public L402Protocol(L402Validator validator, String serviceName) {
    this.validator = Objects.requireNonNull(validator, "validator must not be null");
    this.serviceName = Objects.requireNonNull(serviceName, "serviceName must not be null");
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
    return authorizationHeader.startsWith(L402_PREFIX)
        || authorizationHeader.startsWith(LSAT_PREFIX);
  }

  @Override
  public PaymentCredential parseCredential(String authorizationHeader)
      throws PaymentValidationException {
    try {
      L402Credential credential = L402Credential.parse(authorizationHeader);
      MacaroonIdentifier macId = MacaroonIdentifier.decode(credential.macaroon().identifier());

      return new PaymentCredential(
          macId.paymentHash(),
          credential.preimage().value(),
          credential.tokenId(),
          SCHEME,
          null,
          new L402Metadata(
              credential.macaroon(), credential.additionalMacaroons(), authorizationHeader));
    } catch (L402Exception e) {
      throw mapL402Exception(e);
    }
  }

  @Override
  public ChallengeResponse formatChallenge(ChallengeContext context) {
    byte[] tokenIdBytes = HexFormat.of().parseHex(context.tokenId());
    MacaroonIdentifier identifier =
        new MacaroonIdentifier(MACAROON_IDENTIFIER_VERSION, context.paymentHash(), tokenIdBytes);

    List<Caveat> caveats = new ArrayList<>();
    caveats.add(new Caveat("services", serviceName + ":0"));
    String capability = context.capability();
    if (capability != null && !capability.isBlank()) {
      caveats.add(new Caveat(serviceName + "_capabilities", capability));
    }
    Instant validUntil = Instant.now().plusSeconds(context.timeoutSeconds());
    caveats.add(
        new Caveat(serviceName + "_valid_until", String.valueOf(validUntil.getEpochSecond())));

    Macaroon macaroon = MacaroonMinter.mint(context.rootKeyBytes(), identifier, null, caveats);

    byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
    String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);

    String safeBolt11 = sanitizeBolt11ForHeader(context.bolt11Invoice());
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

  @Override
  public void validate(PaymentCredential credential, Map<String, String> requestContext)
      throws PaymentValidationException {
    Objects.requireNonNull(credential, "credential must not be null");
    Objects.requireNonNull(requestContext, "requestContext must not be null");

    if (!(credential.metadata() instanceof L402Metadata metadata)) {
      throw new PaymentValidationException(
          PaymentValidationException.ErrorCode.MALFORMED_CREDENTIAL,
          "Expected L402Metadata but got " + credential.metadata().getClass().getName(),
          credential.tokenId());
    }

    L402VerificationContext context =
        L402VerificationContext.builder()
            .serviceName(serviceName)
            .currentTime(Instant.now())
            .requestMetadata(requestContext)
            .build();

    try {
      validator.validate(metadata.rawAuthorizationHeader(), context);
    } catch (L402Exception e) {
      throw mapL402Exception(e);
    }
  }

  /**
   * Maps an L402 core {@link ErrorCode} to the protocol-agnostic {@link
   * PaymentValidationException.ErrorCode}.
   */
  private static PaymentValidationException mapL402Exception(L402Exception e) {
    PaymentValidationException.ErrorCode mapped =
        switch (e.getErrorCode()) {
          case MALFORMED_HEADER -> PaymentValidationException.ErrorCode.MALFORMED_CREDENTIAL;
          case INVALID_PREIMAGE -> PaymentValidationException.ErrorCode.INVALID_PREIMAGE;
          case EXPIRED_CREDENTIAL -> PaymentValidationException.ErrorCode.EXPIRED_CREDENTIAL;
          case INVALID_MACAROON, INVALID_SERVICE, REVOKED_CREDENTIAL ->
              PaymentValidationException.ErrorCode.INVALID_CHALLENGE_BINDING;
          case LIGHTNING_UNAVAILABLE -> PaymentValidationException.ErrorCode.SERVICE_UNAVAILABLE;
        };
    return new PaymentValidationException(mapped, e.getMessage(), e.getTokenId());
  }

  /**
   * Validates that a bolt11 invoice string contains no characters that could enable HTTP header
   * injection. Rejects C0 control characters (0x00-0x1F), DEL (0x7F), and double-quote.
   */
  static String sanitizeBolt11ForHeader(String bolt11) {
    if (bolt11 == null) {
      return "";
    }
    for (int i = 0; i < bolt11.length(); i++) {
      char c = bolt11.charAt(i);
      if (c <= 0x1F || c == 0x7F || c == '"') {
        throw new IllegalArgumentException(
            "bolt11 invoice contains illegal character at index "
                + i
                + ": 0x"
                + Integer.toHexString(c));
      }
    }
    return bolt11;
  }
}
