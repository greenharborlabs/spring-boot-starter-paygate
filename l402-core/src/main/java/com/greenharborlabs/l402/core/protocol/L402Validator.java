package com.greenharborlabs.l402.core.protocol;

import com.greenharborlabs.l402.core.credential.CredentialStore;
import com.greenharborlabs.l402.core.macaroon.CaveatVerifier;
import com.greenharborlabs.l402.core.macaroon.L402VerificationContext;
import com.greenharborlabs.l402.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.l402.core.macaroon.MacaroonVerificationException;
import com.greenharborlabs.l402.core.macaroon.MacaroonVerifier;
import com.greenharborlabs.l402.core.macaroon.RootKeyStore;

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
     * Validates an L402 Authorization header and returns the authenticated credential.
     *
     * @param authorizationHeader the raw Authorization header value
     * @return the validated {@link L402Credential}
     * @throws L402Exception on any validation failure
     */
    public L402Credential validate(String authorizationHeader) {
        // 1. Parse the authorization header
        L402Credential credential = L402Credential.parse(authorizationHeader);
        String tokenId = credential.tokenId();

        // 2. Check credential cache — return immediately if found
        L402Credential cached = credentialStore.get(tokenId);
        if (cached != null) {
            return cached;
        }

        // 3. Extract tokenId bytes from macaroon identifier for root key lookup
        MacaroonIdentifier macId = MacaroonIdentifier.decode(credential.macaroon().identifier());
        byte[] tokenIdBytes = macId.tokenId();

        // 4. Look up root key
        byte[] rootKey = rootKeyStore.getRootKey(tokenIdBytes);
        if (rootKey == null) {
            throw new L402Exception(ErrorCode.REVOKED_CREDENTIAL,
                    "No root key found for token", tokenId);
        }

        // 5. Verify macaroon signature (caveat verification happens inside)
        L402VerificationContext context = L402VerificationContext.builder()
                .serviceName(serviceName)
                .currentTime(Instant.now())
                .build();
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
        }

        // 6. Verify preimage matches payment hash
        byte[] paymentHash = macId.paymentHash();
        if (!credential.preimage().matchesHash(paymentHash)) {
            throw new L402Exception(ErrorCode.INVALID_PREIMAGE,
                    "Preimage does not match payment hash", tokenId);
        }

        // 7. Cache the credential
        credentialStore.store(tokenId, credential, DEFAULT_TTL_SECONDS);

        return credential;
    }
}
