package com.greenharborlabs.l402.core.macaroon;

import com.greenharborlabs.l402.core.protocol.ErrorCode;
import com.greenharborlabs.l402.core.protocol.L402Exception;

import java.time.Instant;
import java.util.Objects;

public class ValidUntilCaveatVerifier implements CaveatVerifier {

    private final String serviceName;

    public ValidUntilCaveatVerifier(String serviceName) {
        this.serviceName = Objects.requireNonNull(serviceName, "serviceName must not be null");
    }

    @Override
    public String getKey() {
        return serviceName + "_valid_until";
    }

    @Override
    public void verify(Caveat caveat, L402VerificationContext context) {
        long epochSeconds;
        try {
            epochSeconds = Long.parseLong(caveat.value());
        } catch (NumberFormatException e) {
            throw new L402Exception(ErrorCode.EXPIRED_CREDENTIAL,
                    "Invalid valid_until timestamp: " + caveat.value(), null);
        }
        Instant expiresAt = Instant.ofEpochSecond(epochSeconds);

        if (!expiresAt.isAfter(context.getCurrentTime())) {
            throw new L402Exception(ErrorCode.EXPIRED_CREDENTIAL,
                    "Credential expired at " + expiresAt + " (current time: " + context.getCurrentTime() + ")",
                    null);
        }
    }

    /**
     * Returns {@code true} if the current timestamp is at or before the previous timestamp.
     * A later expiry than the previous caveat would be an escalation.
     */
    @Override
    public boolean isMoreRestrictive(Caveat previous, Caveat current) {
        try {
            long previousEpoch = Long.parseLong(previous.value());
            long currentEpoch = Long.parseLong(current.value());
            return currentEpoch <= previousEpoch;
        } catch (NumberFormatException _) {
            // Malformed timestamps are rejected by verify(); treat as non-restrictive
            return false;
        }
    }
}
