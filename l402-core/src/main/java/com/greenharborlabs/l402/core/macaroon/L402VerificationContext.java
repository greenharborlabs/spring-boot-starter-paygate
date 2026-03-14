package com.greenharborlabs.l402.core.macaroon;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Context object passed to {@link CaveatVerifier} implementations during macaroon verification.
 * Contains the service name, current time, and arbitrary request metadata needed by caveat verifiers.
 */
public class L402VerificationContext {

    private final String serviceName;
    private final Instant currentTime;
    private final Map<String, String> requestMetadata;
    private final String requestedCapability;

    /**
     * No-arg constructor with sensible defaults: null serviceName, current time, empty metadata, no capability.
     */
    public L402VerificationContext() {
        this(null, Instant.now(), Map.of(), null);
    }

    /**
     * Constructor without requestedCapability for backward compatibility.
     *
     * @param serviceName     the service name for {@code services} caveat verification, may be null
     * @param currentTime     the current time for time-based caveat verification, must not be null
     * @param requestMetadata arbitrary key-value metadata for custom caveats, must not be null
     */
    public L402VerificationContext(String serviceName, Instant currentTime, Map<String, String> requestMetadata) {
        this(serviceName, currentTime, requestMetadata, null);
    }

    /**
     * Full constructor.
     *
     * @param serviceName         the service name for {@code services} caveat verification, may be null
     * @param currentTime         the current time for time-based caveat verification, must not be null
     * @param requestMetadata     arbitrary key-value metadata for custom caveats, must not be null
     * @param requestedCapability the capability being requested, may be null (permissive: no specific capability required)
     */
    public L402VerificationContext(String serviceName, Instant currentTime,
                                   Map<String, String> requestMetadata, String requestedCapability) {
        this.serviceName = serviceName;
        this.currentTime = Objects.requireNonNull(currentTime, "currentTime must not be null");
        this.requestMetadata = Collections.unmodifiableMap(new HashMap<>(
                Objects.requireNonNull(requestMetadata, "requestMetadata must not be null")));
        this.requestedCapability = requestedCapability;
    }

    public String getServiceName() {
        return serviceName;
    }

    public Instant getCurrentTime() {
        return currentTime;
    }

    public Map<String, String> getRequestMetadata() {
        return requestMetadata;
    }

    public String getRequestedCapability() {
        return requestedCapability;
    }

    /**
     * Returns a new builder for constructing an {@code L402VerificationContext}.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String serviceName;
        private Instant currentTime = Instant.now();
        private Map<String, String> requestMetadata = Map.of();
        private String requestedCapability;

        private Builder() {}

        public Builder serviceName(String serviceName) {
            this.serviceName = serviceName;
            return this;
        }

        public Builder currentTime(Instant currentTime) {
            this.currentTime = Objects.requireNonNull(currentTime, "currentTime must not be null");
            return this;
        }

        public Builder requestMetadata(Map<String, String> requestMetadata) {
            this.requestMetadata = Objects.requireNonNull(requestMetadata, "requestMetadata must not be null");
            return this;
        }

        public Builder requestedCapability(String requestedCapability) {
            this.requestedCapability = requestedCapability;
            return this;
        }

        public L402VerificationContext build() {
            return new L402VerificationContext(serviceName, currentTime, requestMetadata, requestedCapability);
        }
    }
}
