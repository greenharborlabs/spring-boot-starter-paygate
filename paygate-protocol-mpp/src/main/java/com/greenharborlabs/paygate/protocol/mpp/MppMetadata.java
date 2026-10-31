package com.greenharborlabs.paygate.protocol.mpp;

import com.greenharborlabs.paygate.api.ProtocolMetadata;
import java.util.Map;
import java.util.Objects;

/**
 * Protocol-specific metadata for an MPP (Modern Payment Protocol) credential.
 *
 * @param echoedChallenge the challenge parameters echoed back by the client (defensively copied)
 * @param source optional client-supplied string (may be {@code null}); unverified, not covered by
 *     the challenge HMAC
 */
public record MppMetadata(Map<String, String> echoedChallenge, String source)
    implements ProtocolMetadata {

  /**
   * Compact constructor that validates required fields and defensively copies the challenge map.
   */
  public MppMetadata {
    Objects.requireNonNull(echoedChallenge, "echoedChallenge must not be null");
    echoedChallenge = Map.copyOf(echoedChallenge);
  }
}
