package com.greenharborlabs.l402.core.macaroon;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class MacaroonVerifier {

    private MacaroonVerifier() {}

    /**
     * Verifies a macaroon's HMAC chain signature and evaluates registered caveat verifiers.
     *
     * <p>Unknown caveats are skipped per the L402 macaroons.md spec to support
     * cross-application delegation. Only caveats with a registered verifier are evaluated.
     * The HMAC chain always includes all caveats regardless of whether a verifier exists.
     *
     * <p>For caveats whose key appears more than once, each subsequent occurrence must be
     * at least as restrictive as the previous one (monotonic restriction). If a verifier's
     * {@link CaveatVerifier#isMoreRestrictive} returns {@code false}, verification fails
     * with a caveat escalation error.
     *
     * @param macaroon        the macaroon to verify
     * @param rootKey         the root key used to derive the signing key
     * @param caveatVerifiers registered verifiers for known caveat keys
     * @param context         verification context (service name, current time, etc.)
     * @throws MacaroonVerificationException if the signature is invalid or a caveat fails
     */
    public static void verify(Macaroon macaroon, byte[] rootKey,
                              List<CaveatVerifier> caveatVerifiers,
                              L402VerificationContext context) {
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

            verifyCaveats(macaroon.caveats(), caveatVerifiers, context);
        } finally {
            KeyMaterial.zeroize(derivedKey, sig);
        }
    }

    /**
     * Verifies a list of caveats against registered verifiers and a verification context.
     *
     * <p>Unknown caveats (no registered verifier for the key) are silently skipped.
     * For caveats whose key appears more than once, each subsequent occurrence must be
     * at least as restrictive as the previous one (monotonic restriction).
     *
     * @param caveats         the caveats to verify
     * @param caveatVerifiers registered verifiers for known caveat keys
     * @param context         verification context (service name, current time, etc.)
     * @throws MacaroonVerificationException if a caveat escalation is detected or a verifier rejects
     */
    public static void verifyCaveats(List<Caveat> caveats,
                                     List<CaveatVerifier> caveatVerifiers,
                                     L402VerificationContext context) {
        Map<String, Caveat> lastSeenByKey = new HashMap<>();
        for (Caveat caveat : caveats) {
            CaveatVerifier verifier = findVerifier(caveatVerifiers, caveat.key());
            if (verifier == null) {
                // Unknown caveats are skipped per the L402 spec
                continue;
            }

            Caveat previous = lastSeenByKey.get(caveat.key());
            if (previous != null && !verifier.isMoreRestrictive(previous, caveat)) {
                throw new MacaroonVerificationException(
                        "caveat escalation detected for key: " + caveat.key());
            }
            lastSeenByKey.put(caveat.key(), caveat);

            verifier.verify(caveat, context);
        }
    }

    private static CaveatVerifier findVerifier(List<CaveatVerifier> verifiers, String key) {
        for (CaveatVerifier v : verifiers) {
            if (v.getKey().equals(key)) {
                return v;
            }
        }
        return null;
    }
}
