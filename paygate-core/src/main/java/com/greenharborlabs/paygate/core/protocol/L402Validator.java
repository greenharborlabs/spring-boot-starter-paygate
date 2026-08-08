package com.greenharborlabs.paygate.core.protocol;

import com.greenharborlabs.paygate.api.crypto.SensitiveBytes;
import com.greenharborlabs.paygate.core.credential.CredentialStore;
import com.greenharborlabs.paygate.core.macaroon.CapabilitiesCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.Caveat;
import com.greenharborlabs.paygate.core.macaroon.CaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.KeyMaterial;
import com.greenharborlabs.paygate.core.macaroon.L402VerificationContext;
import com.greenharborlabs.paygate.core.macaroon.Macaroon;
import com.greenharborlabs.paygate.core.macaroon.MacaroonCrypto;
import com.greenharborlabs.paygate.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.paygate.core.macaroon.MacaroonVerificationException;
import com.greenharborlabs.paygate.core.macaroon.MacaroonVerifier;
import com.greenharborlabs.paygate.core.macaroon.RootKeyStore;
import com.greenharborlabs.paygate.core.macaroon.VerificationContextKeys;
import com.greenharborlabs.paygate.core.macaroon.VerificationFailureReason;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Orchestrates L402 credential validation: parse the header, decode the identifier, verify the
 * preimage, require the root key, inspect the cache, verify the macaroon when needed, and cache on
 * success.
 *
 * <p><strong>SECURITY INVARIANT:</strong> Preimage (proof-of-payment) MUST be verified before
 * macaroon signature on all paths. This prevents oracle attacks where an adversary without
 * proof-of-payment can probe macaroon validity through differential error responses.
 */
public final class L402Validator {

  private static final System.Logger log = System.getLogger(L402Validator.class.getName());
  private static final long DEFAULT_TTL_SECONDS = 3600;
  private static final String ROUTE_CAVEAT_KEY = "route";
  private static final String METHOD_CAVEAT_KEY = "method";
  private static final String MISSING_REQUEST_CONTEXT_MESSAGE =
      "Request route and method context are required";
  private static final String REVOKED_CREDENTIAL_MESSAGE = "Credential has been revoked";

  private final RootKeyStore rootKeyStore;
  private final CredentialStore credentialStore;
  private final Map<String, CaveatVerifier> caveatVerifiersByKey;
  private final String serviceName;
  private final String capabilityCaveatKey;
  private final CapabilitiesCaveatVerifier capabilitiesCaveatVerifier;

  public L402Validator(
      RootKeyStore rootKeyStore,
      CredentialStore credentialStore,
      List<CaveatVerifier> caveatVerifiers,
      String serviceName) {
    this.rootKeyStore = Objects.requireNonNull(rootKeyStore, "rootKeyStore must not be null");
    this.credentialStore =
        Objects.requireNonNull(credentialStore, "credentialStore must not be null");
    this.serviceName = Objects.requireNonNull(serviceName, "serviceName must not be null");
    this.capabilityCaveatKey = this.serviceName + "_capabilities";
    List<CaveatVerifier> verifiers =
        List.copyOf(Objects.requireNonNull(caveatVerifiers, "caveatVerifiers must not be null"));
    this.caveatVerifiersByKey = MacaroonVerifier.buildVerifierMap(verifiers);
    requireBoundaryVerifier(ROUTE_CAVEAT_KEY);
    requireBoundaryVerifier(METHOD_CAVEAT_KEY);
    CaveatVerifier capabilityVerifier = requireBoundaryVerifier(capabilityCaveatKey);
    if (!(capabilityVerifier instanceof CapabilitiesCaveatVerifier typedVerifier)) {
      throw new IllegalArgumentException(
          "Required capability caveat verifier must be a CapabilitiesCaveatVerifier");
    }
    this.capabilitiesCaveatVerifier = typedVerifier;
  }

  /**
   * Wraps a validated credential with its validation freshness and effective capabilities.
   *
   * <p>The contained credential is caller-owned. Callers are responsible for destroying it when no
   * longer needed. Fresh validation returns the parsed request credential. Cache hits return a
   * separate caller-owned copy of the credential retrieved from {@link
   * CredentialStore#get(String)}.
   *
   * <p>Effective capabilities are an immutable snapshot. As part of the record state, they
   * participate in the generated equality and hash-code semantics.
   *
   * @param credential the validated caller-owned credential
   * @param freshValidation whether the credential was freshly validated rather than served from
   *     cache
   * @param effectiveCapabilities the non-null capabilities effective for this validation
   */
  public record ValidationResult(
      L402Credential credential, boolean freshValidation, Set<String> effectiveCapabilities) {

    /** Creates a result after defensively snapshotting its effective capabilities. */
    public ValidationResult {
      effectiveCapabilities =
          Set.copyOf(
              Objects.requireNonNull(
                  effectiveCapabilities, "effectiveCapabilities must not be null"));
    }

    /**
     * Creates a result with no effective capabilities.
     *
     * @param credential the validated caller-owned credential
     * @param freshValidation whether the credential was freshly validated rather than served from
     *     cache
     */
    public ValidationResult(L402Credential credential, boolean freshValidation) {
      this(credential, freshValidation, Set.of());
    }
  }

  /**
   * Validates an L402 Authorization header using a default verification context built from the
   * configured service name and the current time. This compatibility overload does not provide the
   * mandatory request route and method, so an otherwise valid credential fails with {@link
   * ErrorCode#MISSING_REQUEST_CONTEXT}. First-party request validation must use {@link
   * #validate(String, L402VerificationContext)}.
   *
   * @param authorizationHeader the raw Authorization header value
   * @return a {@link ValidationResult} containing the credential and freshness flag
   * @throws L402Exception on any validation failure
   */
  public ValidationResult validate(String authorizationHeader) {
    L402VerificationContext defaultContext =
        L402VerificationContext.builder()
            .serviceName(serviceName)
            .currentTime(Instant.now())
            .build();
    return validate(L402HeaderComponents.extractOrThrow(authorizationHeader), defaultContext);
  }

  /**
   * Validates an L402 Authorization header using the provided verification context.
   *
   * @param authorizationHeader the raw Authorization header value
   * @param context the verification context; request metadata must contain non-blank {@link
   *     VerificationContextKeys#REQUEST_ROUTE} and {@link VerificationContextKeys#REQUEST_METHOD}
   * @return a {@link ValidationResult} containing the credential and freshness flag
   * @throws L402Exception on any validation failure
   */
  public ValidationResult validate(String authorizationHeader, L402VerificationContext context) {
    return validate(L402HeaderComponents.extractOrThrow(authorizationHeader), context);
  }

  /**
   * Validates pre-parsed L402 header components using the provided verification context.
   *
   * @param components the structurally validated header components
   * @param context the verification context; request metadata must contain non-blank {@link
   *     VerificationContextKeys#REQUEST_ROUTE} and {@link VerificationContextKeys#REQUEST_METHOD}
   * @return a {@link ValidationResult} containing the credential and freshness flag
   * @throws L402Exception on any validation failure
   */
  public ValidationResult validate(
      L402HeaderComponents components, L402VerificationContext context) {
    Objects.requireNonNull(components, "components must not be null");
    Objects.requireNonNull(context, "context must not be null");

    // 1. Parse the pre-extracted header components
    L402Credential credential;
    try {
      credential = L402Credential.parse(components);
    } catch (L402Exception e) {
      throw new L402Exception(e.getErrorCode(), "Malformed L402 credential", e.getTokenId());
    }
    String tokenId = credential.tokenId();
    boolean returningCredential = false;

    try {
      // 2. Decode the identifier and verify proof-of-payment before consulting or comparing
      //    cached credential variants. This ordering prevents cache membership from becoming a
      //    macaroon/signature oracle for callers that do not possess the payment preimage.
      MacaroonIdentifier macId = MacaroonIdentifier.decode(credential.macaroon().identifier());
      byte[] tokenIdBytes = macId.tokenId();
      try {
        verifyPreimage(credential, macId);

        // 3. Require authoritative root-key state before consulting the credential cache. A cache
        //    hit may reuse prior signature verification, but it may never outlive root-key
        //    revocation. Proof-of-payment remains first so neither store becomes an oracle.
        SensitiveBytes rootKeySb;
        try {
          rootKeySb = rootKeyStore.getRootKey(tokenIdBytes);
        } catch (RuntimeException e) {
          revokeCachedCredentialAfterRootKeyFailure(tokenId);
          throw new L402Exception(
              ErrorCode.REVOKED_CREDENTIAL, REVOKED_CREDENTIAL_MESSAGE, tokenId);
        }
        if (rootKeySb == null) {
          revokeCachedCredentialAfterRootKeyFailure(tokenId);
          throw new L402Exception(
              ErrorCode.REVOKED_CREDENTIAL, REVOKED_CREDENTIAL_MESSAGE, tokenId);
        }

        // The defensive key copy spans cache inspection and fresh validation. Exact hits use it
        // only as an existence check; all exits still close it.
        try (rootKeySb) {
          // 4. Check credential cache — exact variants reuse signature verification and re-run
          //    request-specific caveats. A token ID identifies a cache slot, not an immutable
          //    variant, so changed or attenuated variants continue through full verification.
          L402Credential cached = credentialStore.get(tokenId);
          if (cached != null) {
            try {
              if (credential.macaroon().equals(cached.macaroon())
                  && credential.additionalMacaroons().equals(cached.additionalMacaroons())) {
                return verifyCachedCredential(credential, cached, context);
              }
            } finally {
              cached.destroy();
            }
          }

          // 5. Reuse the already loaded root key for full signature and caveat validation.
          Instant now = context.getCurrentTime();
          Set<String> effectiveCapabilities;
          byte[] rootKey = rootKeySb.value();
          try {
            effectiveCapabilities =
                verifyMacaroon(credential.macaroon(), macId, rootKey, context, tokenId);
          } catch (MacaroonVerificationException e) {
            throw new L402Exception(
                mapReasonToErrorCode(e.getReason()),
                safeValidationFailureMessage(e.getReason()),
                tokenId);
          } finally {
            KeyMaterial.zeroize(rootKey);
          }

          // 6. Cache only after complete signature/caveat validation and return the verified final
          //    capability ceiling.
          long cacheTtl = extractCacheTtl(credential.macaroon(), DEFAULT_TTL_SECONDS, now);
          credentialStore.store(tokenId, credential, cacheTtl);

          returningCredential = true;
          return new ValidationResult(credential, true, effectiveCapabilities);
        }
      } finally {
        KeyMaterial.zeroize(tokenIdBytes);
      }
    } finally {
      if (!returningCredential) {
        credential.destroy();
      }
    }
  }

  private void revokeCachedCredentialAfterRootKeyFailure(String tokenId) {
    try {
      credentialStore.revoke(tokenId);
    } catch (RuntimeException e) {
      log.log(
          System.Logger.Level.WARNING,
          "Credential cache eviction failed after root-key lookup failure ({0})",
          e.getClass().getName());
    }
  }

  /**
   * Validates an exact presented credential variant against a cached credential. Verification
   * order: mandatory boundary presence, then caveats. The caller has already verified the presented
   * preimage against the payment hash before selecting this cached variant.
   *
   * @param credential the presented credential from the request
   * @param cached the previously validated and cached credential
   * @param context the verification context for caveat re-evaluation
   * @return a {@link ValidationResult} with a caller-owned credential copy and
   *     freshValidation=false
   * @throws L402Exception if preimage, macaroon, or caveat verification fails
   */
  private ValidationResult verifyCachedCredential(
      L402Credential credential, L402Credential cached, L402VerificationContext context) {
    String tokenId = credential.tokenId();

    // Recover the authenticated identifier only after the exact-variant equality guard in the
    // caller. Legacy cached entries are rejected and evicted without affecting another variant.
    try {
      MacaroonIdentifier cachedIdentifier =
          MacaroonIdentifier.decode(cached.macaroon().identifier());
      verifyCurrentIdentifierVersion(cachedIdentifier);
      verifyRequiredBoundaryCaveats(cached.macaroon().caveats());
    } catch (IllegalArgumentException | MacaroonVerificationException e) {
      credentialStore.revoke(tokenId);
      VerificationFailureReason reason =
          e instanceof MacaroonVerificationException verificationException
              ? verificationException.getReason()
              : VerificationFailureReason.CAVEAT_NOT_MET;
      throw new L402Exception(
          mapReasonToErrorCode(reason), safeValidationFailureMessage(reason), tokenId);
    }
    requireRequestContext(context, tokenId);
    try {
      MacaroonVerifier.verifyCaveats(cached.macaroon().caveats(), caveatVerifiersByKey, context);
    } catch (MacaroonVerificationException e) {
      if (e.getReason() == VerificationFailureReason.CREDENTIAL_EXPIRED
          || e.getReason() == VerificationFailureReason.CAVEAT_ESCALATION) {
        credentialStore.revoke(tokenId);
      }
      throw new L402Exception(
          mapReasonToErrorCode(e.getReason()),
          safeValidationFailureMessage(e.getReason()),
          tokenId);
    }

    Set<String> effectiveCapabilities = extractFinalEffectiveCapabilities(cached.macaroon());
    return new ValidationResult(cached.copy(), false, effectiveCapabilities);
  }

  private Set<String> verifyMacaroon(
      Macaroon macaroon,
      MacaroonIdentifier identifier,
      byte[] rootKey,
      L402VerificationContext context,
      String tokenId) {
    byte[] derivedKey = MacaroonCrypto.deriveKey(rootKey);
    byte[] sig = null;
    try {
      sig = MacaroonCrypto.hmac(derivedKey, macaroon.identifier());

      for (Caveat caveat : macaroon.caveats()) {
        byte[] oldSig = sig;
        sig = MacaroonCrypto.hmac(oldSig, caveat.toString().getBytes(StandardCharsets.UTF_8));
        KeyMaterial.zeroize(oldSig);
      }

      if (!MacaroonCrypto.constantTimeEquals(sig, macaroon.signature())) {
        throw new MacaroonVerificationException("signature verification failed");
      }

      verifyCurrentIdentifierVersion(identifier);
      verifyRequiredBoundaryCaveats(macaroon.caveats());
      requireRequestContext(context, tokenId);
      MacaroonVerifier.verifyCaveats(macaroon.caveats(), caveatVerifiersByKey, context);
      return extractFinalEffectiveCapabilities(macaroon);
    } finally {
      KeyMaterial.zeroize(derivedKey, sig);
    }
  }

  private static void verifyCurrentIdentifierVersion(MacaroonIdentifier identifier) {
    if (identifier.version() != 1) {
      throw new MacaroonVerificationException(
          VerificationFailureReason.CAVEAT_NOT_MET, "Credential uses a legacy identifier schema");
    }
  }

  private CaveatVerifier requireBoundaryVerifier(String key) {
    CaveatVerifier verifier = caveatVerifiersByKey.get(key);
    if (verifier == null) {
      throw new IllegalArgumentException("Missing required " + key + " caveat verifier");
    }
    return verifier;
  }

  private static void requireRequestContext(L402VerificationContext context, String tokenId) {
    Map<String, String> metadata = context.getRequestMetadata();
    String route = metadata.get(VerificationContextKeys.REQUEST_ROUTE);
    String method = metadata.get(VerificationContextKeys.REQUEST_METHOD);
    if (route == null || route.isBlank() || method == null || method.isBlank()) {
      throw new L402Exception(
          ErrorCode.MISSING_REQUEST_CONTEXT, MISSING_REQUEST_CONTEXT_MESSAGE, tokenId);
    }
  }

  private void verifyRequiredBoundaryCaveats(List<Caveat> caveats) {
    boolean hasRoute = false;
    boolean hasMethod = false;
    boolean hasCapabilityCeiling = false;
    for (Caveat caveat : caveats) {
      hasRoute |= ROUTE_CAVEAT_KEY.equals(caveat.key());
      hasMethod |= METHOD_CAVEAT_KEY.equals(caveat.key());
      hasCapabilityCeiling |= capabilityCaveatKey.equals(caveat.key());
    }
    if (!hasRoute || !hasMethod || !hasCapabilityCeiling) {
      throw new MacaroonVerificationException(
          VerificationFailureReason.CAVEAT_NOT_MET,
          "Credential is missing a required request boundary caveat");
    }
  }

  private Set<String> extractFinalEffectiveCapabilities(Macaroon macaroon) {
    Caveat finalCapabilityCaveat = null;
    for (Caveat caveat : macaroon.caveats()) {
      if (capabilityCaveatKey.equals(caveat.key())) {
        finalCapabilityCaveat = caveat;
      }
    }
    if (finalCapabilityCaveat == null) {
      throw new MacaroonVerificationException(
          VerificationFailureReason.CAVEAT_NOT_MET,
          "Credential is missing a required request boundary caveat");
    }
    return capabilitiesCaveatVerifier.parseEffectiveCapabilities(finalCapabilityCaveat.value());
  }

  /**
   * Verifies that the credential's preimage hashes to the payment hash embedded in the macaroon
   * identifier. Uses constant-time SHA-256 comparison.
   *
   * <p>This check is performed early in the validation pipeline (before root key lookup and
   * signature verification) to uphold the security invariant: an adversary who does not possess
   * proof-of-payment must not be able to learn anything about macaroon validity from error
   * responses.
   *
   * @param credential the credential containing the preimage to verify
   * @param macId the decoded macaroon identifier containing the expected payment hash
   * @throws L402Exception with {@link ErrorCode#INVALID_PREIMAGE} if the preimage does not match
   */
  private void verifyPreimage(L402Credential credential, MacaroonIdentifier macId) {
    byte[] paymentHash = macId.paymentHash();
    if (!credential.preimage().matchesHash(paymentHash)) {
      throw new L402Exception(
          ErrorCode.INVALID_PREIMAGE,
          "Presented preimage does not match payment hash",
          credential.tokenId());
    }
  }

  private static ErrorCode mapReasonToErrorCode(VerificationFailureReason reason) {
    if (reason == null) {
      return ErrorCode.INVALID_MACAROON;
    }
    return switch (reason) {
      case CAVEAT_NOT_MET -> ErrorCode.INVALID_SERVICE;
      case CREDENTIAL_EXPIRED -> ErrorCode.EXPIRED_CREDENTIAL;
      case SIGNATURE_INVALID, CAVEAT_ESCALATION -> ErrorCode.INVALID_MACAROON;
    };
  }

  private static String safeValidationFailureMessage(VerificationFailureReason reason) {
    if (reason == null) {
      return "Credential validation failed";
    }
    return switch (reason) {
      case CAVEAT_NOT_MET -> "Credential constraints were not satisfied";
      case CREDENTIAL_EXPIRED -> "Credential has expired";
      case SIGNATURE_INVALID -> "Credential signature verification failed";
      case CAVEAT_ESCALATION -> "Credential attenuation is invalid";
    };
  }

  /**
   * Derives cache TTL from {@code valid_until} caveats on the macaroon. If one or more {@code
   * {serviceName}_valid_until} caveats are present, the TTL is the minimum remaining time until any
   * of them expire, capped by {@code defaultTtlSeconds}. Returns {@code defaultTtlSeconds} if no
   * matching caveat is found.
   */
  private long extractCacheTtl(Macaroon macaroon, long defaultTtlSeconds, Instant now) {
    String validUntilKey = serviceName + "_valid_until";
    long nowEpoch = now.getEpochSecond();
    long minRemaining = defaultTtlSeconds;
    boolean found = false;

    for (Caveat caveat : macaroon.caveats()) {
      if (validUntilKey.equals(caveat.key())) {
        try {
          long expiryEpoch = Long.parseLong(caveat.value());
          // Guard against pathological values that could cause overflow in arithmetic.
          // Accept only expiry values within a reasonable window around now.
          long minAllowedExpiry = nowEpoch - defaultTtlSeconds;
          long maxAllowedExpiry = nowEpoch + defaultTtlSeconds + 86_400L; // +1 day margin
          if (expiryEpoch < minAllowedExpiry || expiryEpoch > maxAllowedExpiry) {
            // Skip unreasonable expiry values; caveat verifier enforces semantic validity.
            continue;
          }

          long remaining = expiryEpoch - nowEpoch;
          // Floor at 1 second — the caveat verifier already rejected expired tokens,
          // so remaining should be positive, but guard against clock skew.
          remaining = Math.max(remaining, 1L);
          if (!found || remaining < minRemaining) {
            minRemaining = remaining;
          }
          found = true;
        } catch (NumberFormatException _) {
          // Malformed value — skip, caveat verifier is responsible for enforcement
        }
      }
    }

    // Subtract 30s safety margin to prevent using cached credentials that are about to expire.
    // Floor at 1 second to ensure a positive TTL.
    if (found) {
      minRemaining = Math.max(minRemaining - 30, 1L);
    }
    return Math.min(minRemaining, defaultTtlSeconds);
  }
}
