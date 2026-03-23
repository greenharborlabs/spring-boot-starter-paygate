package com.greenharborlabs.paygate.protocol.mpp;

import java.util.Map;
import java.util.Objects;

import com.greenharborlabs.paygate.api.ProtocolMetadata;

/**
 * Protocol-specific metadata for an MPP (402 Multi-Part Payment) credential.
 *
 * @param echoedChallenge the challenge parameters echoed back by the client (defensively copied)
 * @param source          optional identifier for the credential source (may be {@code null})
 * @param rawCredentialJson the raw JSON credential string as received from the client
 */
public record MppMetadata(
        Map<String, String> echoedChallenge,
        String source,
        String rawCredentialJson
) implements ProtocolMetadata {

    /**
     * Compact constructor that validates required fields and defensively copies the challenge map.
     */
    public MppMetadata {
        Objects.requireNonNull(echoedChallenge, "echoedChallenge must not be null");
        Objects.requireNonNull(rawCredentialJson, "rawCredentialJson must not be null");
        echoedChallenge = Map.copyOf(echoedChallenge);
    }
}
