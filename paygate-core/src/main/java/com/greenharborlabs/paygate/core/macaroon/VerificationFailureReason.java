package com.greenharborlabs.paygate.core.macaroon;

/**
 * Semantic failure categories for macaroon verification.
 *
 * <p>This enum captures why a macaroon failed verification at the
 * cryptographic/caveat layer. It intentionally carries no HTTP status
 * codes or protocol-specific semantics.</p>
 */
public enum VerificationFailureReason {

    /** Root or discharge signature does not match. */
    SIGNATURE_INVALID,

    /** A caveat condition was not satisfied by the request context. */
    CAVEAT_NOT_MET,

    /** The credential has expired (e.g., valid-until caveat in the past). */
    CREDENTIAL_EXPIRED,

    /** A presented caveat attempts to widen permissions beyond the original grant. */
    CAVEAT_ESCALATION
}
