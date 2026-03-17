package com.greenharborlabs.l402.lightning.lnbits;

/**
 * Configuration for the LNbits Lightning backend.
 *
 * @param baseUrl                the base URL of the LNbits instance (e.g. "https://lnbits.example.com/")
 * @param apiKey                 the invoice/read API key for authentication via X-Api-Key header
 * @param requestTimeoutSeconds  HTTP request timeout in seconds (must be positive)
 * @param connectTimeoutSeconds  TCP connect timeout in seconds (must be positive)
 */
public record LnbitsConfig(String baseUrl, String apiKey, int requestTimeoutSeconds,
                            int connectTimeoutSeconds) {

    private static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 5;
    private static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;

    public LnbitsConfig {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be null or blank");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey must not be null or blank");
        }
        if (requestTimeoutSeconds <= 0) {
            throw new IllegalArgumentException(
                    "requestTimeoutSeconds must be positive, got: " + requestTimeoutSeconds);
        }
        if (connectTimeoutSeconds <= 0) {
            throw new IllegalArgumentException(
                    "connectTimeoutSeconds must be positive, got: " + connectTimeoutSeconds);
        }
    }

    /**
     * Convenience constructor that uses the default request timeout of 5 seconds
     * and the default connect timeout of 10 seconds.
     */
    public LnbitsConfig(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, DEFAULT_REQUEST_TIMEOUT_SECONDS, DEFAULT_CONNECT_TIMEOUT_SECONDS);
    }

    /**
     * Backwards-compatible constructor that uses the default connect timeout of 10 seconds.
     */
    public LnbitsConfig(String baseUrl, String apiKey, int requestTimeoutSeconds) {
        this(baseUrl, apiKey, requestTimeoutSeconds, DEFAULT_CONNECT_TIMEOUT_SECONDS);
    }

    @Override
    public String toString() {
        return "LnbitsConfig[baseUrl=" + baseUrl + ", apiKey=***REDACTED***"
                + ", requestTimeoutSeconds=" + requestTimeoutSeconds
                + ", connectTimeoutSeconds=" + connectTimeoutSeconds + "]";
    }
}
