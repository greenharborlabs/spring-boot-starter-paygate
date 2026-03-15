package com.greenharborlabs.l402.core.protocol;

import com.greenharborlabs.l402.core.credential.CredentialStore;
import com.greenharborlabs.l402.core.macaroon.Caveat;
import com.greenharborlabs.l402.core.macaroon.CaveatVerifier;
import com.greenharborlabs.l402.core.macaroon.L402VerificationContext;
import com.greenharborlabs.l402.core.macaroon.Macaroon;
import com.greenharborlabs.l402.core.macaroon.KeyMaterial;
import com.greenharborlabs.l402.core.macaroon.MacaroonCrypto;
import com.greenharborlabs.l402.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.l402.core.macaroon.MacaroonVerificationException;
import com.greenharborlabs.l402.core.macaroon.MacaroonVerifier;
import com.greenharborlabs.l402.core.macaroon.RootKeyStore;
import com.greenharborlabs.l402.core.macaroon.SensitiveBytes;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Orchestrates L402 credential validation: parse header, check cache, verify macaroon
 * signature, verify preimage, run caveat verifiers, and cache on success.
 */
public final class L402Validator {

    private static final long DEFAULT_TTL_SECONDS = 3600;

    private final RootKeyStore rootKeyStore;
    private final CredentialStore credentialStore;
    private final List<CaveatVerifier> caveatVerifiers;
    private final String serviceName;

    public L402Validator(RootKeyStore rootKeyStore, CredentialStore credentialStore,
                         List<CaveatVerifier> caveatVerifiers, String serviceName) {
        this.rootKeyStore = Objects.requireNonNull(rootKeyStore, "rootKeyStore must not be null");
        this.credentialStore = Objects.requireNonNull(credentialStore, "credentialStore must not be null");
        this.caveatVerifiers = List.copyOf(Objects.requireNonNull(caveatVerifiers, "caveatVerifiers must not be null"));
        this.serviceName = Objects.requireNonNull(serviceName, "serviceName must not be null");
    }

    /**
     * Wraps a validated credential with a flag indicating whether
     * it was freshly validated (true) or served from cache (false).
     */
    public record ValidationResult(L402Credential credential, boolean freshValidation) {}

    /**
     * Validates an L402 Authorization header using a default verification context
     * built from the configured service name and the current time.
     *
     * @param authorizationHeader the raw Authorization header value
     * @return a {@link ValidationResult} containing the credential and freshness flag
     * @throws L402Exception on any validation failure
     */
    public ValidationResult validate(String authorizationHeader) {
        L402VerificationContext defaultContext = L402VerificationContext.builder()
                .serviceName(serviceName)
                .currentTime(Instant.now())
                .build();
        return validate(authorizationHeader, defaultContext);
    }

    /**
     * Validates an L402 Authorization header using the provided verification context.
     *
     * @param authorizationHeader the raw Authorization header value
     * @param context             the verification context (service name, current time, capabilities, etc.)
     * @return a {@link ValidationResult} containing the credential and freshness flag
     * @throws L402Exception on any validation failure
     */
    public ValidationResult validate(String authorizationHeader, L402VerificationContext context) {
        Objects.requireNonNull(context, "context must not be null");

        // 1. Parse the authorization header
        L402Credential credential = L402Credential.parse(authorizationHeader);
        String tokenId = credential.tokenId();

        // 2. Check credential cache — verify presented credential matches cached, re-verify caveats.
        //    Root key existence is NOT re-checked here: the credential was fully validated
        //    (including root key + HMAC) before it entered the cache. If a root key is revoked,
        //    the revoking code should proactively call credentialStore.revoke() to evict it.
        L402Credential cached = credentialStore.get(tokenId);
        if (cached != null) {
            // Verify the presented macaroon signature matches the cached one
            if (!MacaroonCrypto.constantTimeEquals(
                    credential.macaroon().signature(), cached.macaroon().signature())) {
                throw new L402Exception(ErrorCode.INVALID_MACAROON,
                        "Presented macaroon signature does not match cached credential", tokenId);
            }

            // Verify the presented preimage matches the cached one
            if (!credential.preimage().equals(cached.preimage())) {
                throw new L402Exception(ErrorCode.INVALID_MACAROON,
                        "Presented preimage does not match cached credential", tokenId);
            }

            // Re-verify all caveats against the provided context (includes escalation detection)
            try {
                MacaroonVerifier.verifyCaveats(cached.macaroon().caveats(), caveatVerifiers, context);
            } catch (MacaroonVerificationException e) {
                credentialStore.revoke(tokenId);
                throw new L402Exception(ErrorCode.INVALID_MACAROON,
                        "Macaroon verification failed: " + e.getMessage(), tokenId);
            } catch (L402Exception e) {
                credentialStore.revoke(tokenId);
                if (e.getTokenId() == null) {
                    throw new L402Exception(e.getErrorCode(), e.getMessage(), tokenId);
                }
                throw e;
            }

            return new ValidationResult(cached, false);
        }

        // 3. Extract tokenId bytes from macaroon identifier for root key lookup
        MacaroonIdentifier macId = MacaroonIdentifier.decode(credential.macaroon().identifier());
        byte[] tokenIdBytes = macId.tokenId();

        // 4. Look up root key
        SensitiveBytes rootKeySb = rootKeyStore.getRootKey(tokenIdBytes);
        if (rootKeySb == null) {
            throw new L402Exception(ErrorCode.REVOKED_CREDENTIAL,
                    "No root key found for token", tokenId);
        }

        // 5. Verify macaroon signature and caveats using the provided context
        Instant now = context.getCurrentTime();
        try (rootKeySb) {
            byte[] rootKey = rootKeySb.value();
            try {
                MacaroonVerifier.verify(credential.macaroon(), rootKey, caveatVerifiers, context);
            } catch (MacaroonVerificationException e) {
                throw new L402Exception(ErrorCode.INVALID_MACAROON,
                        "Macaroon verification failed: " + e.getMessage(), tokenId);
            } catch (L402Exception e) {
                // Caveat verifiers throw L402Exception with the correct ErrorCode
                // (EXPIRED_CREDENTIAL, INVALID_SERVICE) but without tokenId context.
                // Re-throw with tokenId enriched if missing.
                if (e.getTokenId() == null) {
                    throw new L402Exception(e.getErrorCode(), e.getMessage(), tokenId);
                }
                throw e;
            } finally {
                KeyMaterial.zeroize(rootKey);
            }
        }

        // 6. Verify preimage matches payment hash
        byte[] paymentHash = macId.paymentHash();
        if (!credential.preimage().matchesHash(paymentHash)) {
            throw new L402Exception(ErrorCode.INVALID_PREIMAGE,
                    "Preimage does not match payment hash", tokenId);
        }

        // 7. Cache the credential with TTL derived from valid_until caveats
        long cacheTtl = extractCacheTtl(credential.macaroon(), DEFAULT_TTL_SECONDS, now);
        credentialStore.store(tokenId, credential, cacheTtl);

        return new ValidationResult(credential, true);
    }

    /**
     * Derives cache TTL from {@code valid_until} caveats on the macaroon.
     * If one or more {@code {serviceName}_valid_until} caveats are present, the TTL is
     * the minimum remaining time until any of them expire, capped by {@code defaultTtlSeconds}.
     * Returns {@code defaultTtlSeconds} if no matching caveat is found.
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
