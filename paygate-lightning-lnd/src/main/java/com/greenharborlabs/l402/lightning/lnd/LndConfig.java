package com.greenharborlabs.l402.lightning.lnd;

/**
 * Configuration for connecting to an LND node over gRPC.
 *
 * @param host                    LND gRPC host
 * @param port                    LND gRPC port
 * @param tlsCertPath             path to TLS certificate file, or null when allowPlaintext is true
 * @param macaroonPath            path to admin macaroon file, or null for unauthenticated/test channels
 * @param allowPlaintext          if true, permits unencrypted connections (testing only)
 * @param keepAliveTimeSeconds    interval between gRPC keepalive pings
 * @param keepAliveTimeoutSeconds timeout waiting for a keepalive ping acknowledgement
 * @param idleTimeoutMinutes      close the channel after this many idle minutes
 * @param maxInboundMessageSize   maximum inbound gRPC message size in bytes
 * @param rpcDeadlineSeconds      deadline for individual gRPC calls in seconds
 */
public record LndConfig(
        String host,
        int port,
        String tlsCertPath,
        String macaroonPath,
        boolean allowPlaintext,
        int keepAliveTimeSeconds,
        int keepAliveTimeoutSeconds,
        int idleTimeoutMinutes,
        int maxInboundMessageSize,
        int rpcDeadlineSeconds
) {

    private static final int DEFAULT_KEEP_ALIVE_TIME_SECONDS = 60;
    private static final int DEFAULT_KEEP_ALIVE_TIMEOUT_SECONDS = 20;
    private static final int DEFAULT_IDLE_TIMEOUT_MINUTES = 5;
    private static final int DEFAULT_MAX_INBOUND_MESSAGE_SIZE = 4_194_304; // 4 MB
    static final int DEFAULT_RPC_DEADLINE_SECONDS = 5;

    public LndConfig {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be null or blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535, got: " + port);
        }
        if (!allowPlaintext && tlsCertPath == null) {
            throw new IllegalArgumentException(
                    "tlsCertPath must not be null when allowPlaintext is false");
        }
        if (keepAliveTimeSeconds <= 0) {
            throw new IllegalArgumentException(
                    "keepAliveTimeSeconds must be positive, got: " + keepAliveTimeSeconds);
        }
        if (keepAliveTimeoutSeconds <= 0) {
            throw new IllegalArgumentException(
                    "keepAliveTimeoutSeconds must be positive, got: " + keepAliveTimeoutSeconds);
        }
        if (idleTimeoutMinutes <= 0) {
            throw new IllegalArgumentException(
                    "idleTimeoutMinutes must be positive, got: " + idleTimeoutMinutes);
        }
        if (maxInboundMessageSize <= 0) {
            throw new IllegalArgumentException(
                    "maxInboundMessageSize must be positive, got: " + maxInboundMessageSize);
        }
        if (rpcDeadlineSeconds <= 0) {
            throw new IllegalArgumentException(
                    "rpcDeadlineSeconds must be positive, got: " + rpcDeadlineSeconds);
        }
    }

    /**
     * Creates a config with sensible defaults for connection management fields.
     * {@code allowPlaintext} defaults to {@code false}.
     */
    public static LndConfig withDefaults(String host, int port, String tlsCertPath, String macaroonPath) {
        return new LndConfig(
                host, port, tlsCertPath, macaroonPath,
                false,
                DEFAULT_KEEP_ALIVE_TIME_SECONDS,
                DEFAULT_KEEP_ALIVE_TIMEOUT_SECONDS,
                DEFAULT_IDLE_TIMEOUT_MINUTES,
                DEFAULT_MAX_INBOUND_MESSAGE_SIZE,
                DEFAULT_RPC_DEADLINE_SECONDS
        );
    }

    /**
     * Creates a plaintext config suitable for testing (no TLS, no macaroon).
     */
    public static LndConfig plaintextForTesting(String host, int port) {
        return new LndConfig(
                host, port, null, null,
                true,
                DEFAULT_KEEP_ALIVE_TIME_SECONDS,
                DEFAULT_KEEP_ALIVE_TIMEOUT_SECONDS,
                DEFAULT_IDLE_TIMEOUT_MINUTES,
                DEFAULT_MAX_INBOUND_MESSAGE_SIZE,
                DEFAULT_RPC_DEADLINE_SECONDS
        );
    }
}
