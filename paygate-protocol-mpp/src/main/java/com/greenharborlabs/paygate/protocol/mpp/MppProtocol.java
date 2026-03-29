package com.greenharborlabs.paygate.protocol.mpp;

import com.greenharborlabs.paygate.api.ChallengeContext;
import com.greenharborlabs.paygate.api.ChallengeResponse;
import com.greenharborlabs.paygate.api.PaymentCredential;
import com.greenharborlabs.paygate.api.PaymentProtocol;
import com.greenharborlabs.paygate.api.PaymentReceipt;
import com.greenharborlabs.paygate.api.PaymentValidationException;
import com.greenharborlabs.paygate.api.PaymentValidationException.ErrorCode;
import com.greenharborlabs.paygate.api.crypto.CryptoUtils;
import com.greenharborlabs.paygate.api.crypto.SensitiveBytes;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * MPP (Multi-Part Payment) protocol implementation for HTTP 402 payment challenges.
 *
 * <p>Uses the {@code Payment} authentication scheme with HMAC-SHA256 challenge binding for
 * stateless server-side verification. All validation follows a security-critical order: preimage
 * hash check, then HMAC binding, then expiry.
 *
 * <p>Zero external dependencies -- JDK only.
 */
public final class MppProtocol implements PaymentProtocol {

  private static final String SCHEME = "Payment";
  private static final String SCHEME_PREFIX = "payment ";
  private static final int MIN_SECRET_LENGTH = 32;
  private static final HexFormat HEX = HexFormat.of();

  private final SensitiveBytes challengeBindingSecret;
  private final MppParserLimits parserLimits;

  /**
   * Creates a new MPP protocol instance with default parser limits.
   *
   * @param challengeBindingSecret HMAC secret for challenge binding (minimum 32 bytes)
   * @throws NullPointerException if secret is null
   * @throws IllegalArgumentException if secret is shorter than 32 bytes
   */
  public MppProtocol(SensitiveBytes challengeBindingSecret) {
    this(challengeBindingSecret, MppParserLimits.defaults());
  }

  /**
   * Creates a new MPP protocol instance with custom parser limits.
   *
   * @param challengeBindingSecret HMAC secret for challenge binding (minimum 32 bytes)
   * @param parserLimits limits for JSON parser resource exhaustion protection
   * @throws NullPointerException if secret or parserLimits is null
   * @throws IllegalArgumentException if secret is shorter than 32 bytes
   */
  public MppProtocol(SensitiveBytes challengeBindingSecret, MppParserLimits parserLimits) {
    Objects.requireNonNull(challengeBindingSecret, "challengeBindingSecret must not be null");
    Objects.requireNonNull(parserLimits, "parserLimits must not be null");
    byte[] temp = challengeBindingSecret.value();
    try {
      if (temp.length < MIN_SECRET_LENGTH) {
        throw new IllegalArgumentException(
            "challengeBindingSecret must be at least "
                + MIN_SECRET_LENGTH
                + " bytes, got "
                + temp.length);
      }
    } finally {
      CryptoUtils.zeroize(temp);
    }
    this.challengeBindingSecret = challengeBindingSecret;
    this.parserLimits = parserLimits;
  }

  @Override
  public String scheme() {
    return SCHEME;
  }

  @Override
  public boolean canHandle(String authorizationHeader) {
    if (authorizationHeader == null || authorizationHeader.length() < SCHEME_PREFIX.length()) {
      return false;
    }
    return authorizationHeader.regionMatches(true, 0, SCHEME_PREFIX, 0, SCHEME_PREFIX.length());
  }

  @Override
  public PaymentCredential parseCredential(String authorizationHeader)
      throws PaymentValidationException {
    Objects.requireNonNull(authorizationHeader, "authorizationHeader must not be null");

    // Strip "Payment " prefix (case-insensitive match already done via canHandle)
    String blob = authorizationHeader.substring(SCHEME_PREFIX.length());

    PaymentCredential credential = MppCredentialParser.parse(blob, parserLimits);

    // Verify method is "lightning"
    if (credential.metadata() instanceof MppMetadata mppMetadata) {
      String method = mppMetadata.echoedChallenge().get("method");
      if (!"lightning".equals(method)) {
        throw new PaymentValidationException(
            ErrorCode.METHOD_UNSUPPORTED,
            "Unsupported payment method: " + method,
            credential.tokenId());
      }
    }

    return credential;
  }

  @Override
  public ChallengeResponse formatChallenge(ChallengeContext context) {
    Objects.requireNonNull(context, "context must not be null");

    String realm = context.serviceName();

    // Build the charge request
    String paymentHashHex = HEX.formatHex(context.paymentHash());
    var methodDetails =
        new LightningChargeRequest.MethodDetails(
            context.bolt11Invoice(), paymentHashHex, "mainnet");
    var chargeRequest =
        new LightningChargeRequest(
            String.valueOf(context.priceSats()), "BTC", context.description(), methodDetails);

    // JCS serialize and base64url-nopad encode the request
    String jcs = JcsSerializer.serialize(chargeRequest.toJcsMap());
    String requestB64 =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(jcs.getBytes(StandardCharsets.UTF_8));

    // RFC 3339 expires
    String expires =
        DateTimeFormatter.ISO_INSTANT.format(Instant.now().plusSeconds(context.timeoutSeconds()));

    // Handle opaque
    String opaqueB64 = null;
    if (context.opaque() != null) {
      String opaqueJcs = JcsSerializer.serialize(new LinkedHashMap<>(context.opaque()));
      opaqueB64 =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(opaqueJcs.getBytes(StandardCharsets.UTF_8));
    }

    // Compute HMAC challenge ID
    String id =
        MppChallengeBinding.createId(
            realm,
            "lightning",
            "charge",
            requestB64,
            expires,
            context.digest(),
            opaqueB64,
            challengeBindingSecret);

    // Build WWW-Authenticate header
    var header = new StringBuilder(512);
    header.append(SCHEME).append(' ');
    header.append("id=\"").append(id).append("\", ");
    header.append("realm=\"").append(sanitizeHeaderValue(realm)).append("\", ");
    header.append("method=\"lightning\", ");
    header.append("intent=\"charge\", ");
    header.append("request=\"").append(requestB64).append("\", ");
    header.append("expires=\"").append(expires).append("\"");

    if (context.description() != null && !context.description().isEmpty()) {
      header
          .append(", description=\"")
          .append(sanitizeHeaderValue(context.description()))
          .append("\"");
    }

    if (opaqueB64 != null) {
      header.append(", opaque=\"").append(opaqueB64).append("\"");
    }

    // Build body data for JSON response
    var bodyData = new LinkedHashMap<String, Object>();
    bodyData.put("id", id);
    bodyData.put("realm", realm);
    bodyData.put("method", "lightning");
    bodyData.put("intent", "charge");
    bodyData.put("request", requestB64);
    bodyData.put("expires", expires);
    if (context.description() != null && !context.description().isEmpty()) {
      bodyData.put("description", context.description());
    }
    if (opaqueB64 != null) {
      bodyData.put("opaque", opaqueB64);
    }

    return new ChallengeResponse(header.toString(), SCHEME, bodyData);
  }

  @Override
  @SuppressWarnings("PMD.CyclomaticComplexity") // Security-critical validation — order matters
  public void validate(PaymentCredential credential, Map<String, String> requestContext)
      throws PaymentValidationException {
    Objects.requireNonNull(credential, "credential must not be null");
    Objects.requireNonNull(requestContext, "requestContext must not be null");

    if (!(credential.metadata() instanceof MppMetadata mppMetadata)) {
      throw new PaymentValidationException(
          ErrorCode.MALFORMED_CREDENTIAL,
          "Expected MppMetadata but got " + credential.metadata().getClass().getName(),
          credential.tokenId());
    }

    Map<String, String> challenge = mppMetadata.echoedChallenge();

    // Extract echoed challenge fields
    String id = challenge.get("id");
    String realm = challenge.get("realm");
    String method = challenge.get("method");
    String intent = challenge.get("intent");
    String request = challenge.get("request");
    String expires = challenge.get("expires");
    String digest = challenge.get("digest");
    String opaque = challenge.get("opaque");

    // ---- Security-critical validation order ----

    // 1. Preimage hash check (before HMAC to prevent oracle attacks)
    byte[] computedHash = MppCryptoUtils.sha256(credential.preimage());
    if (!MppCryptoUtils.constantTimeEquals(computedHash, credential.paymentHash())) {
      throw new PaymentValidationException(
          ErrorCode.INVALID_PREIMAGE, "Preimage does not match payment hash", credential.tokenId());
    }

    // 2. HMAC binding check
    if (id == null || realm == null || method == null || intent == null || request == null) {
      throw new PaymentValidationException(
          ErrorCode.INVALID_CHALLENGE_BINDING,
          "Echoed challenge is missing required fields",
          credential.tokenId());
    }
    boolean hmacValid =
        MppChallengeBinding.verify(
            id, realm, method, intent, request, expires, digest, opaque, challengeBindingSecret);
    if (!hmacValid) {
      throw new PaymentValidationException(
          ErrorCode.INVALID_CHALLENGE_BINDING,
          "Challenge binding verification failed",
          credential.tokenId());
    }

    // 3. Expiry check
    if (expires != null) {
      Instant expiresInstant;
      try {
        expiresInstant = Instant.parse(expires);
      } catch (DateTimeParseException e) {
        throw new PaymentValidationException(
            ErrorCode.EXPIRED_CREDENTIAL, "Invalid expires timestamp format", credential.tokenId());
      }
      if (Instant.now().isAfter(expiresInstant)) {
        throw new PaymentValidationException(
            ErrorCode.EXPIRED_CREDENTIAL, "Credential has expired", credential.tokenId());
      }
    }

    // 4. Defense-in-depth: method must be "lightning"
    if (!"lightning".equals(method)) {
      throw new PaymentValidationException(
          ErrorCode.METHOD_UNSUPPORTED,
          "Unsupported payment method: " + method,
          credential.tokenId());
    }
  }

  @Override
  public Optional<PaymentReceipt> createReceipt(
      PaymentCredential credential, ChallengeContext context) {
    return Optional.of(MppReceipt.from(credential, context));
  }

  /**
   * Sanitizes a string for use in an HTTP header quoted-string value. Rejects control characters
   * and double-quotes to prevent header injection.
   */
  private static String sanitizeHeaderValue(String value) {
    if (value == null) {
      return "";
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c <= 0x1F || c == 0x7F || c == '"') {
        throw new IllegalArgumentException(
            "Header value contains illegal character at index "
                + i
                + ": 0x"
                + Integer.toHexString(c));
      }
    }
    return value;
  }
}
