package com.greenharborlabs.l402.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the L402 Spring Boot starter.
 *
 * <p>Bound from the {@code l402.*} namespace in application configuration.
 */
@ConfigurationProperties("l402")
public class L402Properties {

    private boolean enabled = false;

    private String backend;

    /**
     * Default price for L402-protected endpoints, in satoshis.
     * <p>Note: The L402 protocol specification recommends milli-satoshis (1/1000th of a satoshi),
     * but this library uses satoshis for practical simplicity. BOLT 11 invoices handle the
     * conversion internally.
     */
    private long defaultPriceSats = 10;

    private long defaultTimeoutSeconds = 3600;

    private String serviceName;

    private String rootKeyStore = "file";

    private String rootKeyStorePath = "~/.l402/keys";

    private int credentialCacheMaxSize = 10000;

    private String securityMode = "auto";

    private boolean testMode = false;

    private boolean trustForwardedHeaders = false;

    private RateLimit rateLimit = new RateLimit();

    private HealthCache healthCache = new HealthCache();

    private Lnbits lnbits = new Lnbits();

    private Lnd lnd = new Lnd();

    private Lightning lightning = new Lightning();

    private Metrics metrics = new Metrics();

    public Lightning getLightning() {
        return lightning;
    }

    public void setLightning(Lightning lightning) {
        this.lightning = lightning;
    }

    public Metrics getMetrics() {
        return metrics;
    }

    public void setMetrics(Metrics metrics) {
        this.metrics = metrics;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
    }

    public HealthCache getHealthCache() {
        return healthCache;
    }

    public void setHealthCache(HealthCache healthCache) {
        this.healthCache = healthCache;
    }

    public Lnbits getLnbits() {
        return lnbits;
    }

    public void setLnbits(Lnbits lnbits) {
        this.lnbits = lnbits;
    }

    public Lnd getLnd() {
        return lnd;
    }

    public void setLnd(Lnd lnd) {
        this.lnd = lnd;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBackend() {
        return backend;
    }

    public void setBackend(String backend) {
        this.backend = backend;
    }

    public long getDefaultPriceSats() {
        return defaultPriceSats;
    }

    public void setDefaultPriceSats(long defaultPriceSats) {
        this.defaultPriceSats = defaultPriceSats;
    }

    public long getDefaultTimeoutSeconds() {
        return defaultTimeoutSeconds;
    }

    public void setDefaultTimeoutSeconds(long defaultTimeoutSeconds) {
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getRootKeyStore() {
        return rootKeyStore;
    }

    public void setRootKeyStore(String rootKeyStore) {
        this.rootKeyStore = rootKeyStore;
    }

    public String getRootKeyStorePath() {
        return rootKeyStorePath;
    }

    public void setRootKeyStorePath(String rootKeyStorePath) {
        this.rootKeyStorePath = rootKeyStorePath;
    }

    public int getCredentialCacheMaxSize() {
        return credentialCacheMaxSize;
    }

    public void setCredentialCacheMaxSize(int credentialCacheMaxSize) {
        this.credentialCacheMaxSize = credentialCacheMaxSize;
    }

    public String getSecurityMode() {
        return securityMode;
    }

    public void setSecurityMode(String securityMode) {
        this.securityMode = securityMode;
    }

    public boolean isTestMode() {
        return testMode;
    }

    public void setTestMode(boolean testMode) {
        this.testMode = testMode;
    }

    public boolean isTrustForwardedHeaders() {
        return trustForwardedHeaders;
    }

    public void setTrustForwardedHeaders(boolean trustForwardedHeaders) {
        this.trustForwardedHeaders = trustForwardedHeaders;
    }

    /**
     * Rate-limiting configuration bound from {@code l402.rate-limit.*}.
     */
    public static class RateLimit {

        private double requestsPerSecond = 10.0;

        private int burstSize = 20;

        public double getRequestsPerSecond() {
            return requestsPerSecond;
        }

        public void setRequestsPerSecond(double requestsPerSecond) {
            this.requestsPerSecond = requestsPerSecond;
        }

        public int getBurstSize() {
            return burstSize;
        }

        public void setBurstSize(int burstSize) {
            this.burstSize = burstSize;
        }
    }

    /**
     * Health-check caching configuration bound from {@code l402.health-cache.*}.
     */
    public static class HealthCache {

        private boolean enabled = true;

        private int ttlSeconds = 5;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(int ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }
    }

    /**
     * Global Lightning backend configuration bound from {@code l402.lightning.*}.
     */
    public static class Lightning {

        private int timeoutSeconds = 5;

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            if (timeoutSeconds <= 0) {
                throw new IllegalArgumentException(
                        "l402.lightning.timeout-seconds must be > 0, got: " + timeoutSeconds);
            }
            this.timeoutSeconds = timeoutSeconds;
        }
    }

    /**
     * LNbits backend configuration properties bound from {@code l402.lnbits.*}.
     */
    public static class Lnbits {

        private String url;

        private String apiKey;

        private Integer requestTimeoutSeconds;

        private Integer connectTimeoutSeconds;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public Integer getRequestTimeoutSeconds() {
            return requestTimeoutSeconds;
        }

        public void setRequestTimeoutSeconds(Integer requestTimeoutSeconds) {
            if (requestTimeoutSeconds != null && requestTimeoutSeconds <= 0) {
                throw new IllegalArgumentException(
                        "l402.lnbits.request-timeout-seconds must be > 0, got: " + requestTimeoutSeconds);
            }
            this.requestTimeoutSeconds = requestTimeoutSeconds;
        }

        public Integer getConnectTimeoutSeconds() {
            return connectTimeoutSeconds;
        }

        public void setConnectTimeoutSeconds(Integer connectTimeoutSeconds) {
            if (connectTimeoutSeconds != null && connectTimeoutSeconds <= 0) {
                throw new IllegalArgumentException(
                        "l402.lnbits.connect-timeout-seconds must be > 0, got: " + connectTimeoutSeconds);
            }
            this.connectTimeoutSeconds = connectTimeoutSeconds;
        }
    }

    /**
     * LND backend configuration properties bound from {@code l402.lnd.*}.
     */
    public static class Lnd {

        private String host = "localhost";

        private int port = 10009;

        private String tlsCertPath;

        private String macaroonPath;

        private boolean allowPlaintext = false;

        private int keepAliveTimeSeconds = 60;

        private int keepAliveTimeoutSeconds = 20;

        private int idleTimeoutMinutes = 5;

        private int maxInboundMessageSize = 4194304;

        private Integer rpcDeadlineSeconds;

        public boolean isAllowPlaintext() {
            return allowPlaintext;
        }

        public void setAllowPlaintext(boolean allowPlaintext) {
            this.allowPlaintext = allowPlaintext;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getTlsCertPath() {
            return tlsCertPath;
        }

        public void setTlsCertPath(String tlsCertPath) {
            this.tlsCertPath = tlsCertPath;
        }

        public String getMacaroonPath() {
            return macaroonPath;
        }

        public void setMacaroonPath(String macaroonPath) {
            this.macaroonPath = macaroonPath;
        }

        public int getKeepAliveTimeSeconds() {
            return keepAliveTimeSeconds;
        }

        public void setKeepAliveTimeSeconds(int keepAliveTimeSeconds) {
            this.keepAliveTimeSeconds = keepAliveTimeSeconds;
        }

        public int getKeepAliveTimeoutSeconds() {
            return keepAliveTimeoutSeconds;
        }

        public void setKeepAliveTimeoutSeconds(int keepAliveTimeoutSeconds) {
            this.keepAliveTimeoutSeconds = keepAliveTimeoutSeconds;
        }

        public int getIdleTimeoutMinutes() {
            return idleTimeoutMinutes;
        }

        public void setIdleTimeoutMinutes(int idleTimeoutMinutes) {
            this.idleTimeoutMinutes = idleTimeoutMinutes;
        }

        public int getMaxInboundMessageSize() {
            return maxInboundMessageSize;
        }

        public void setMaxInboundMessageSize(int maxInboundMessageSize) {
            this.maxInboundMessageSize = maxInboundMessageSize;
        }

        public Integer getRpcDeadlineSeconds() {
            return rpcDeadlineSeconds;
        }

        public void setRpcDeadlineSeconds(Integer rpcDeadlineSeconds) {
            if (rpcDeadlineSeconds != null && rpcDeadlineSeconds <= 0) {
                throw new IllegalArgumentException(
                        "l402.lnd.rpc-deadline-seconds must be > 0, got: " + rpcDeadlineSeconds);
            }
            this.rpcDeadlineSeconds = rpcDeadlineSeconds;
        }
    }

    /**
     * Metrics configuration bound from {@code l402.metrics.*}.
     */
    public static class Metrics {

        private int maxEndpointCardinality = 100;

        private String overflowTagValue = "_other";

        public int getMaxEndpointCardinality() {
            return maxEndpointCardinality;
        }

        public void setMaxEndpointCardinality(int maxEndpointCardinality) {
            if (maxEndpointCardinality < 1) {
                throw new IllegalArgumentException(
                        "l402.metrics.max-endpoint-cardinality must be >= 1, got: " + maxEndpointCardinality);
            }
            this.maxEndpointCardinality = maxEndpointCardinality;
        }

        public String getOverflowTagValue() {
            return overflowTagValue;
        }

        public void setOverflowTagValue(String overflowTagValue) {
            this.overflowTagValue = overflowTagValue;
        }
    }
}
