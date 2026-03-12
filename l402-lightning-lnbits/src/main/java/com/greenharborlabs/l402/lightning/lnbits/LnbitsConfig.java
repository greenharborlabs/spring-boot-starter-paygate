package com.greenharborlabs.l402.lightning.lnbits;

/**
 * Configuration for the LNbits Lightning backend.
 *
 * @param baseUrl the base URL of the LNbits instance (e.g. "https://lnbits.example.com/")
 * @param apiKey  the invoice/read API key for authentication via X-Api-Key header
 */
public record LnbitsConfig(String baseUrl, String apiKey) {

    public LnbitsConfig {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be null or blank");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey must not be null or blank");
        }
    }

    @Override
    public String toString() {
        return "LnbitsConfig[baseUrl=" + baseUrl + ", apiKey=***REDACTED***]";
    }
}
