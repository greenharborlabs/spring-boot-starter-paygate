package com.greenharborlabs.paygate.core.macaroon;

import com.greenharborlabs.paygate.core.protocol.ErrorCode;
import com.greenharborlabs.paygate.core.protocol.L402Exception;

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

            Map<String, CaveatVerifier> verifiersByKey = buildVerifierMap(caveatVerifiers);
            verifyCaveats(macaroon.caveats(), verifiersByKey, context);
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
     * <p>This overload builds the verifier lookup map on each call. For repeated
     * invocations with the same verifier list, prefer
     * {@link #verifyCaveats(List, Map, L402VerificationContext)} with a pre-built map
     * from {@link #buildVerifierMap(List)}.
     *
     * @param caveats         the caveats to verify
     * @param caveatVerifiers registered verifiers for known caveat keys
     * @param context         verification context (service name, current time, etc.)
     * @throws MacaroonVerificationException if a caveat escalation is detected or a verifier rejects
     */
    public static void verifyCaveats(List<Caveat> caveats,
                                     List<CaveatVerifier> caveatVerifiers,
                                     L402VerificationContext context) {
        verifyCaveats(caveats, buildVerifierMap(caveatVerifiers), context);
    }

    /**
     * Verifies a list of caveats using a pre-built verifier lookup map.
     *
     * <p>Unknown caveats (no registered verifier for the key) are silently skipped.
     * For caveats whose key appears more than once, each subsequent occurrence must be
     * at least as restrictive as the previous one (monotonic restriction).
     *
     * @param caveats        the caveats to verify
     * @param verifiersByKey pre-built map from caveat key to verifier (see {@link #buildVerifierMap(List)})
     * @param context        verification context (service name, current time, etc.)
     * @throws MacaroonVerificationException if a caveat escalation is detected or a verifier rejects
     */
    public static void verifyCaveats(List<Caveat> caveats,
                                     Map<String, CaveatVerifier> verifiersByKey,
                                     L402VerificationContext context) {
        Map<String, Caveat> lastSeenByKey = new HashMap<>();
        for (Caveat caveat : caveats) {
            CaveatVerifier verifier = verifiersByKey.get(caveat.key());
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

        // Post-loop enforcement: if a capability is required, the macaroon MUST contain
        // a capabilities caveat. Without this check, a macaroon lacking the caveat
        // entirely would bypass capability enforcement.
        String requestedCapability = context.getRequestedCapability();
        if (requestedCapability != null && context.getServiceName() != null) {
            String capabilitiesKey = context.getServiceName() + "_capabilities";
            if (!lastSeenByKey.containsKey(capabilitiesKey)) {
                throw new L402Exception(ErrorCode.INVALID_SERVICE,
                        "Macaroon missing required capabilities caveat for capability '"
                                + requestedCapability + "'", null);
            }
        }
    }

    /**
     * Builds a lookup map from caveat key to verifier. The resulting map can be reused
     * across multiple calls to {@link #verifyCaveats(List, Map, L402VerificationContext)}
     * to avoid rebuilding it per request.
     *
     * @param caveatVerifiers the verifier list to index
     * @return unmodifiable map from caveat key to verifier
     */
    public static Map<String, CaveatVerifier> buildVerifierMap(List<CaveatVerifier> caveatVerifiers) {
        Map<String, CaveatVerifier> map = new HashMap<>(caveatVerifiers.size());
        for (CaveatVerifier cv : caveatVerifiers) {
            map.put(cv.getKey(), cv);
        }
        return Map.copyOf(map);
    }
}
