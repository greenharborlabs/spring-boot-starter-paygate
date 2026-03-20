package com.greenharborlabs.paygate.core.macaroon;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Mints new macaroons by computing the HMAC-SHA256 signature chain
 * over the identifier and caveats.
 */
public final class MacaroonMinter {

    private MacaroonMinter() {}

    /**
     * Creates a new macaroon with the given parameters.
     *
     * @param rootKey    the 32-byte root key for key derivation
     * @param identifier the macaroon identifier containing version, paymentHash, tokenId
     * @param location   optional location hint (may be {@code null})
     * @param caveats    ordered list of first-party caveats
     * @return a fully signed {@link Macaroon}
     */
    public static Macaroon mint(byte[] rootKey, MacaroonIdentifier identifier,
                                 String location, List<Caveat> caveats) {
        Objects.requireNonNull(rootKey, "rootKey must not be null");
        Objects.requireNonNull(identifier, "identifier must not be null");
        Objects.requireNonNull(caveats, "caveats must not be null");

        byte[] identifierBytes = MacaroonIdentifier.encode(identifier);
        byte[] derivedKey = MacaroonCrypto.deriveKey(rootKey);
        byte[] sig = null;
        try {
            sig = MacaroonCrypto.hmac(derivedKey, identifierBytes);

            for (Caveat caveat : caveats) {
                byte[] oldSig = sig;
                sig = MacaroonCrypto.hmac(oldSig, caveat.toString().getBytes(StandardCharsets.UTF_8));
                KeyMaterial.zeroize(oldSig);
            }

            return new Macaroon(identifierBytes, location, caveats, sig);
        } finally {
            KeyMaterial.zeroize(sig);
            KeyMaterial.zeroize(derivedKey);
        }
    }
}
