package com.greenharborlabs.l402.lightning.lnbits;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LnbitsConfigTest {

    private static final String BASE_URL = "https://lnbits.example.com";
    private static final String API_KEY = "super-secret-api-key-12345";

    @Test
    void toStringShouldRedactApiKey() {
        var config = new LnbitsConfig(BASE_URL, API_KEY);
        String result = config.toString();

        assertThat(result).contains(BASE_URL);
        assertThat(result).contains("***REDACTED***");
        assertThat(result).doesNotContain(API_KEY);
    }

    @Test
    void accessorsShouldReturnOriginalValues() {
        var config = new LnbitsConfig(BASE_URL, API_KEY);

        assertThat(config.baseUrl()).isEqualTo(BASE_URL);
        assertThat(config.apiKey()).isEqualTo(API_KEY);
    }

    @Test
    void equalityShouldUseActualValues() {
        var config1 = new LnbitsConfig(BASE_URL, API_KEY);
        var config2 = new LnbitsConfig(BASE_URL, API_KEY);

        assertThat(config1).isEqualTo(config2);
        assertThat(config1.hashCode()).isEqualTo(config2.hashCode());
    }

    @Test
    void shouldRejectNullBaseUrl() {
        assertThatThrownBy(() -> new LnbitsConfig(null, API_KEY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectBlankApiKey() {
        assertThatThrownBy(() -> new LnbitsConfig(BASE_URL, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
