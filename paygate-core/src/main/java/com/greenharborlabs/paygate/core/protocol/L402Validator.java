package com.greenharborlabs.paygate.core.protocol;

import com.greenharborlabs.paygate.api.crypto.SensitiveBytes;
import com.greenharborlabs.paygate.core.credential.CredentialStore;
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
import com.greenharborlabs.paygate.core.macaroon.VerificationFailureReason;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Orchestrates L402 credential validation: parse header, check cache, verify preimage, verify
 * macaroon signature, run caveat verifiers, and cache on success.
 *
 * <p><strong>SECURITY INVARIANT:</strong> Preimage (proof-of-payment) MUST be verified before
 * macaroon signature on all paths. This prevents oracle attacks where an adversary without
 * proof-of-payment can probe macaroon validity through differential error responses.
 */
public final class L402Validator {

  private static final long DEFAULT_TTL_SECONDS = 3600;

  private final RootKeyStore rootKeyStore;
  private final CredentialStore credentialStore;
  private final List<CaveatVerifier> caveatVerifiers;
  private final String serviceName;

  public L402Validator(
      RootKeyStore rootKeyStore,
      CredentialStore credentialStore,
      List<CaveatVerifier> caveatVerifiers,
      String serviceName) {
    this.rootKeyStore = Objects.requireNonNull(rootKeyStore, "rootKeyStore must not be null");
    this.credentialStore =
        Objects.requireNonNull(credentialStore, "credentialStore must not be null");
    this.caveatVerifiers =
        List.copyOf(Objects.requireNonNull(caveatVerifiers, "caveatVerifiers must not be null"));
    this.serviceName = Objects.requireNonNull(serviceName, "serviceName must not be null");
  }

  /**
   * Wraps a validated credential with a flag indicating whether it was freshly validated (true) or
   * served from cache (false).
   */
  public record ValidationResult(L402Credential credential, boolean freshValidation) {}

  /**
   * Validates an L402 Authorization header using a default verification context built from the
   * configured service name and the current time.
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
   * @param context the verification context (service name, current time, capabilities, etc.)
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
   * @param context the verification context (service name, current time, capabilities, etc.)
   * @return a {@link ValidationResult} containing the credential and freshness flag
   * @throws L402Exception on any validation failure
   */
  public ValidationResult validate(
      L402HeaderComponents components, L402VerificationContext context) {
    Objects.requireNonNull(components, "components must not be null");
    Objects.requireNonNull(context, "context must not be null");

    // 1. Parse the pre-extracted header components
    L402Credential credential = L402Credential.parse(components);
    String tokenId = credential.tokenId();

    // 2. Check credential cache — verify presented credential matches cached, re-verify caveats.
    //    Root key existence is NOT re-checked here: the credential was fully validated
    //    (including root key + HMAC) before it entered the cache. If a root key is revoked,
    //    the revoking code should proactively call credentialStore.revoke() to evict it.
    L402Credential cached = credentialStore.get(tokenId);
    if (cached != null) {
      return verifyCachedCredential(credential, cached, context);
    }

    // 3. Decode identifier
    MacaroonIdentifier macId = MacaroonIdentifier.decode(credential.macaroon().identifier());
    byte[] tokenIdBytes = macId.tokenId();

    // 4. Verify preimage matches payment hash (BEFORE root key lookup — see security invariant)
    verifyPreimage(credential, macId);

    // 5. Look up root key
    SensitiveBytes rootKeySb = rootKeyStore.getRootKey(tokenIdBytes);
    if (rootKeySb == null) {
      throw new L402Exception(ErrorCode.REVOKED_CREDENTIAL, "No root key found for token", tokenId);
    }

    // 6. Verify macaroon signature and caveats using the provided context
    Instant now = context.getCurrentTime();
    try (rootKeySb) {
      byte[] rootKey = rootKeySb.value();
      try {
        MacaroonVerifier.verify(credential.macaroon(), rootKey, caveatVerifiers, context);
      } catch (MacaroonVerificationException e) {
        throw new L402Exception(mapReasonToErrorCode(e.getReason()), e.getMessage(), tokenId);
      } finally {
        KeyMaterial.zeroize(rootKey);
      }
    }

    // 7. Cache the credential with TTL derived from valid_until caveats
    long cacheTtl = extractCacheTtl(credential.macaroon(), DEFAULT_TTL_SECONDS, now);
    credentialStore.store(tokenId, credential, cacheTtl);

    return new ValidationResult(credential, true);
  }

  /**
   * Validates a presented credential against a cached credential. Verification order: preimage,
   * then signature, then caveats.
   *
   * <p>Preimage is checked first to uphold the security invariant: proof-of-payment must be
   * verified before any macaroon-structural checks, preventing an adversary without payment proof
   * from probing signature validity.
   *
   * @param credential the presented credential from the request
   * @param cached the previously validated and cached credential
   * @param context the verification context for caveat re-evaluation
   * @return a {@link ValidationResult} with the cached credential and freshValidation=false
   * @throws L402Exception if preimage, signature, or caveat verification fails
   */
  private ValidationResult verifyCachedCredential(
      L402Credential credential, L402Credential cached, L402VerificationContext context) {
    String tokenId = credential.tokenId();

    // Verify preimage first (security invariant: proof-of-payment before signature)
    if (!credential.preimage().equals(cached.preimage())) {
      throw new L402Exception(
          ErrorCode.INVALID_PREIMAGE,
          "Presented preimage does not match cached credential",
          tokenId);
    }

    // Verify the presented macaroon signature matches the cached one
    if (!MacaroonCrypto.constantTimeEquals(
        credential.macaroon().signature(), cached.macaroon().signature())) {
      throw new L402Exception(
          ErrorCode.INVALID_MACAROON,
          "Presented macaroon signature does not match cached credential",
          tokenId);
    }

    // Re-verify all caveats against the provided context (includes escalation detection)
    try {
      MacaroonVerifier.verifyCaveats(cached.macaroon().caveats(), caveatVerifiers, context);
    } catch (MacaroonVerificationException e) {
      credentialStore.revoke(tokenId);
      throw new L402Exception(mapReasonToErrorCode(e.getReason()), e.getMessage(), tokenId);
    }

    return new ValidationResult(cached, false);
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
          ErrorCode.INVALID_PREIMAGE, "Preimage does not match payment hash", credential.tokenId());
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
