package com.greenharborlabs.paygate.protocol.mpp;

import com.greenharborlabs.paygate.api.ChallengeContext;
import com.greenharborlabs.paygate.api.ChallengeResponse;
import com.greenharborlabs.paygate.api.PaymentCredential;
import com.greenharborlabs.paygate.api.PaymentProtocol;
import com.greenharborlabs.paygate.api.PaymentReceipt;
import com.greenharborlabs.paygate.api.PaymentValidationException;
import com.greenharborlabs.paygate.api.PaymentValidationException.ErrorCode;
import com.greenharborlabs.paygate.api.SecurityBounds;
import com.greenharborlabs.paygate.api.UnsupportedPaymentMethodException;
import com.greenharborlabs.paygate.api.crypto.CryptoUtils;
import com.greenharborlabs.paygate.api.crypto.SensitiveBytes;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
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
public final class MppProtocol implements PaymentProtocol, AutoCloseable {

  private static final String SCHEME = "Payment";
  private static final String SCHEME_PREFIX = "payment ";
  private static final String REQUEST_DIGEST_KEY = "request.digest";
  private static final int MIN_SECRET_LENGTH = 32;
  private static final Duration MIN_CHALLENGE_LIFETIME =
      Duration.ofSeconds(SecurityBounds.MIN_LIFETIME_SECONDS);
  private static final Duration MAX_CHALLENGE_LIFETIME =
      Duration.ofSeconds(SecurityBounds.MAX_LIFETIME_SECONDS);
  private static final HexFormat HEX = HexFormat.of();

  private final SensitiveBytes challengeBindingSecret;
  private final SensitiveBytes previousChallengeBindingSecret;
  private final MppParserLimits parserLimits;
  private final Clock clock;
  private final Object lifecycleLock = new Object();
  private volatile boolean closed;

  /**
   * Creates a new MPP protocol instance with default parser limits.
   *
   * @param challengeBindingSecret HMAC secret for challenge binding (minimum 32 bytes)
   * @throws NullPointerException if secret is null
   * @throws IllegalArgumentException if secret is shorter than 32 bytes
   */
  public MppProtocol(SensitiveBytes challengeBindingSecret) {
    this(challengeBindingSecret, MppParserLimits.defaults(), null, Clock.systemUTC());
  }

  /**
   * Creates a new MPP protocol instance with a clock used for challenge creation and expiry
   * validation.
   *
   * @param challengeBindingSecret HMAC secret for challenge binding (minimum 32 bytes)
   * @param clock time source for challenge creation and expiry validation
   */
  public MppProtocol(SensitiveBytes challengeBindingSecret, Clock clock) {
    this(challengeBindingSecret, MppParserLimits.defaults(), null, clock);
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
    this(challengeBindingSecret, parserLimits, null, Clock.systemUTC());
  }

  /**
   * Creates a new MPP protocol instance with default parser limits and optional previous secret for
   * key rotation.
   *
   * @param challengeBindingSecret current HMAC secret for challenge binding (minimum 32 bytes)
   * @param previousChallengeBindingSecret previous HMAC secret accepted during rotation (minimum 32
   *     bytes), or null
   * @throws NullPointerException if challengeBindingSecret is null
   * @throws IllegalArgumentException if any provided secret is shorter than 32 bytes
   */
  public MppProtocol(
      SensitiveBytes challengeBindingSecret, SensitiveBytes previousChallengeBindingSecret) {
    this(
        challengeBindingSecret,
        MppParserLimits.defaults(),
        previousChallengeBindingSecret,
        Clock.systemUTC());
  }

  /**
   * Creates a new MPP protocol instance with custom parser limits and optional previous secret for
   * key rotation.
   *
   * @param challengeBindingSecret current HMAC secret for challenge binding (minimum 32 bytes)
   * @param parserLimits limits for JSON parser resource exhaustion protection
   * @param previousChallengeBindingSecret previous HMAC secret accepted during rotation (minimum 32
   *     bytes), or null
   * @throws NullPointerException if challengeBindingSecret or parserLimits is null
   * @throws IllegalArgumentException if any provided secret is shorter than 32 bytes
   */
  public MppProtocol(
      SensitiveBytes challengeBindingSecret,
      MppParserLimits parserLimits,
      SensitiveBytes previousChallengeBindingSecret) {
    this(challengeBindingSecret, parserLimits, previousChallengeBindingSecret, Clock.systemUTC());
  }

  /**
   * Creates a new MPP protocol instance with custom parser limits, optional key rotation, and an
   * explicit time source.
   *
   * @param challengeBindingSecret current HMAC secret for challenge binding (minimum 32 bytes)
   * @param parserLimits limits for JSON parser resource exhaustion protection
   * @param previousChallengeBindingSecret previous HMAC secret accepted during rotation (minimum 32
   *     bytes), or null
   * @param clock time source for challenge creation and expiry validation
   * @throws NullPointerException if challengeBindingSecret, parserLimits, or clock is null
   * @throws IllegalArgumentException if any provided secret is shorter than 32 bytes
   */
  public MppProtocol(
      SensitiveBytes challengeBindingSecret,
      MppParserLimits parserLimits,
      SensitiveBytes previousChallengeBindingSecret,
      Clock clock) {
    Objects.requireNonNull(challengeBindingSecret, "challengeBindingSecret must not be null");
    Objects.requireNonNull(parserLimits, "parserLimits must not be null");
    Objects.requireNonNull(clock, "clock must not be null");
    validateSecretLength("challengeBindingSecret", challengeBindingSecret);
    validateSecretLength("previousChallengeBindingSecret", previousChallengeBindingSecret);
    this.challengeBindingSecret = copySecret(challengeBindingSecret);
    this.previousChallengeBindingSecret = copySecret(previousChallengeBindingSecret);
    this.parserLimits = parserLimits;
    this.clock = clock;
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

    PaymentCredential credential;
    try {
      credential = MppCredentialParser.parse(blob, parserLimits);
    } catch (PaymentValidationException e) {
      // Parser diagnostics may contain attacker-controlled input. Retain only its classification.
      throw mapFailure(e.getErrorCode(), e.getTokenId());
    } catch (IllegalArgumentException e) {
      // Treat unexpected input-decoding failures as malformed credentials, never as server errors.
      throw mapFailure(ErrorCode.MALFORMED, null);
    }

    // Verify method is "lightning"
    if (credential.metadata() instanceof MppMetadata mppMetadata) {
      String method = mppMetadata.echoedChallenge().get("method");
      if (!"lightning".equals(method)) {
        throw new UnsupportedPaymentMethodException(credential.tokenId());
      }
    }

    return credential;
  }

  @Override
  public ChallengeResponse formatChallenge(ChallengeContext context) {
    synchronized (lifecycleLock) {
      requireOpen();
      Objects.requireNonNull(context, "context must not be null");
      if (context.digest() == null || context.digest().isBlank()) {
        throw new IllegalArgumentException("MPP challenge digest must not be null or blank");
      }

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
          DateTimeFormatter.ISO_INSTANT.format(
              clock.instant().plusSeconds(context.timeoutSeconds()));

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
      header.append(", digest=\"").append(sanitizeHeaderValue(context.digest())).append("\"");

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
      bodyData.put("digest", context.digest());
      if (context.description() != null && !context.description().isEmpty()) {
        bodyData.put("description", context.description());
      }
      if (opaqueB64 != null) {
        bodyData.put("opaque", opaqueB64);
      }

      return new ChallengeResponse(header.toString(), SCHEME, bodyData);
    }
  }

  @Override
  @SuppressWarnings("PMD.CyclomaticComplexity") // Security-critical validation — order matters
  public void validate(PaymentCredential credential, Map<String, String> requestContext)
      throws PaymentValidationException {
    synchronized (lifecycleLock) {
      requireOpen();
      Objects.requireNonNull(credential, "credential must not be null");
      Objects.requireNonNull(requestContext, "requestContext must not be null");

      if (!(credential.metadata() instanceof MppMetadata mppMetadata)) {
        throw new PaymentValidationException(
            ErrorCode.MALFORMED,
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
      String requestDigest = requestContext.get(REQUEST_DIGEST_KEY);

      // ---- Security-critical validation order ----

      byte[] preimage = null;
      byte[] paymentHash = null;
      byte[] computedHash = null;
      try {
        // 1. Preimage hash check (before HMAC to prevent oracle attacks)
        preimage = credential.preimage();
        paymentHash = credential.paymentHash();
        computedHash = MppCryptoUtils.sha256(preimage);
        if (!MppCryptoUtils.constantTimeEquals(computedHash, paymentHash)) {
          throw new PaymentValidationException(
              ErrorCode.INVALID, "Preimage does not match payment hash", credential.tokenId());
        }

        // 2. HMAC binding check
        if (id == null || realm == null || method == null || intent == null || request == null) {
          throw new PaymentValidationException(
              ErrorCode.INVALID,
              "Echoed challenge is missing required fields",
              credential.tokenId());
        }
        if (digest == null || digest.isBlank()) {
          throw new PaymentValidationException(
              ErrorCode.INVALID,
              "Echoed challenge is missing digest binding",
              credential.tokenId());
        }
        if (requestDigest == null || requestDigest.isBlank()) {
          throw new PaymentValidationException(
              ErrorCode.INVALID, "Request context is missing digest binding", credential.tokenId());
        }
        if (!requestDigest.equals(digest)) {
          throw new PaymentValidationException(
              ErrorCode.INVALID, "Request digest mismatch", credential.tokenId());
        }
        boolean hmacCurrentSecretValid;
        boolean hmacPreviousSecretValid = false;
        try {
          hmacCurrentSecretValid =
              MppChallengeBinding.verify(
                  id,
                  realm,
                  method,
                  intent,
                  request,
                  expires,
                  digest,
                  opaque,
                  challengeBindingSecret);
          if (!hmacCurrentSecretValid && previousChallengeBindingSecret != null) {
            hmacPreviousSecretValid =
                MppChallengeBinding.verify(
                    id,
                    realm,
                    method,
                    intent,
                    request,
                    expires,
                    digest,
                    opaque,
                    previousChallengeBindingSecret);
          }
        } catch (IllegalArgumentException e) {
          throw mapFailure(ErrorCode.INVALID, credential.tokenId());
        }
        if (!(hmacCurrentSecretValid || hmacPreviousSecretValid)) {
          throw new PaymentValidationException(
              ErrorCode.INVALID, "Challenge binding verification failed", credential.tokenId());
        }

        // 3. Expiry check. Expiry is HMAC-bound above and is mandatory for replay protection.
        if (expires == null || expires.isBlank()) {
          throw new PaymentValidationException(
              ErrorCode.INVALID, "Credential expiry is required", credential.tokenId());
        }
        Instant expiresInstant;
        try {
          expiresInstant = Instant.parse(expires);
        } catch (DateTimeParseException e) {
          throw new PaymentValidationException(
              ErrorCode.INVALID, "Invalid expires timestamp format", credential.tokenId());
        }
        Instant now = clock.instant();
        if (expiresInstant.isBefore(now)) {
          throw new PaymentValidationException(
              ErrorCode.INVALID, "Credential has expired", credential.tokenId());
        }
        Duration lifetime = Duration.between(now, expiresInstant);
        if (lifetime.compareTo(MIN_CHALLENGE_LIFETIME) < 0
            || lifetime.compareTo(MAX_CHALLENGE_LIFETIME) > 0) {
          throw new PaymentValidationException(
              ErrorCode.INVALID,
              "Credential expiry is outside supported lifetime",
              credential.tokenId());
        }

        // 4. Defense-in-depth: method must be "lightning"
        if (!"lightning".equals(method)) {
          throw new UnsupportedPaymentMethodException(credential.tokenId());
        }
      } finally {
        CryptoUtils.zeroize(preimage, paymentHash, computedHash);
      }
    }
  }

  @Override
  public Optional<PaymentReceipt> createReceipt(
      PaymentCredential credential, ChallengeContext context) {
    synchronized (lifecycleLock) {
      requireOpen();
      return Optional.of(MppReceipt.from(credential, context));
    }
  }

  /**
   * Zeroizes protocol-owned binding secrets. Repeated calls are safe, and operations after closing
   * fail closed as unavailable rather than attempting to use destroyed key material.
   */
  @Override
  public void close() {
    synchronized (lifecycleLock) {
      if (!closed) {
        challengeBindingSecret.close();
        if (previousChallengeBindingSecret != null) {
          previousChallengeBindingSecret.close();
        }
        closed = true;
      }
    }
  }

  /**
   * Sanitizes a string for use in an HTTP header quoted-string value. Rejects control characters,
   * double-quotes, and backslashes to prevent header injection via quoted-string escape sequences
   * (RFC 9110 Section 5.6.4).
   */
  private static String sanitizeHeaderValue(String value) {
    if (value == null) {
      return "";
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c <= 0x1F || c == 0x7F || c == '"' || c == '\\') {
        throw new IllegalArgumentException(
            "Header value contains illegal character at index "
                + i
                + ": 0x"
                + Integer.toHexString(c));
      }
    }
    return value;
  }

  /**
   * Produces a protocol-agnostic validation failure without retaining parser or validation detail.
   *
   * <p>MPP credentials are bearer material. In particular, parser exception text can include a
   * supplied credential fragment, so callers must receive only the stable shared taxonomy.
   */
  private static PaymentValidationException mapFailure(ErrorCode errorCode, String tokenId) {
    return new PaymentValidationException(errorCode, "MPP credential validation failed", tokenId);
  }

  private static void validateSecretLength(String fieldName, SensitiveBytes secret) {
    if (secret == null) {
      return;
    }
    byte[] temp = secret.value();
    try {
      if (temp.length < MIN_SECRET_LENGTH) {
        throw new IllegalArgumentException(
            fieldName + " must be at least " + MIN_SECRET_LENGTH + " bytes, got " + temp.length);
      }
    } finally {
      CryptoUtils.zeroize(temp);
    }
  }

  private static SensitiveBytes copySecret(SensitiveBytes secret) {
    if (secret == null) {
      return null;
    }
    byte[] secretCopy = secret.value();
    return new SensitiveBytes(secretCopy);
  }

  private void requireOpen() {
    if (closed) {
      throw new PaymentValidationException(ErrorCode.UNAVAILABLE, "MPP protocol is unavailable");
    }
  }
}
