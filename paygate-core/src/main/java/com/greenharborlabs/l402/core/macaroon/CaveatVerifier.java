package com.greenharborlabs.l402.core.macaroon;

public interface CaveatVerifier {
    String getKey();
    void verify(Caveat caveat, L402VerificationContext context);

    /**
     * Returns whether {@code current} is at least as restrictive as {@code previous}
     * for the same caveat key. Implementations should return {@code false} if the
     * current caveat grants broader access than the previous one (escalation).
     *
     * <p>The default returns {@code true}, making monotonic enforcement opt-in.
     *
     * @param previous the earlier caveat with the same key
     * @param current  the later caveat with the same key
     * @return {@code true} if the restriction is monotonically non-expanding
     */
    default boolean isMoreRestrictive(Caveat previous, Caveat current) {
        return true;
    }
}
