package com.greenharborlabs.l402.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class L402PropertiesParsingTest {

    @Test
    void defaultValues() {
        var parsing = new L402Properties.Parsing();

        assertThat(parsing.getMaxTokens()).isEqualTo(5);
        assertThat(parsing.getMaxCaveats()).isEqualTo(20);
        assertThat(parsing.getMaxMacaroonBytes()).isEqualTo(4096);
    }

    @Test
    void customValues() {
        var parsing = new L402Properties.Parsing();

        parsing.setMaxTokens(10);
        parsing.setMaxCaveats(50);
        parsing.setMaxMacaroonBytes(8192);

        assertThat(parsing.getMaxTokens()).isEqualTo(10);
        assertThat(parsing.getMaxCaveats()).isEqualTo(50);
        assertThat(parsing.getMaxMacaroonBytes()).isEqualTo(8192);
    }

    @Test
    void minimumValidValues() {
        var parsing = new L402Properties.Parsing();

        parsing.setMaxTokens(1);
        parsing.setMaxCaveats(1);
        parsing.setMaxMacaroonBytes(1);

        assertThat(parsing.getMaxTokens()).isEqualTo(1);
        assertThat(parsing.getMaxCaveats()).isEqualTo(1);
        assertThat(parsing.getMaxMacaroonBytes()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -100, Integer.MIN_VALUE})
    void maxTokensRejectsInvalidValues(int invalid) {
        var parsing = new L402Properties.Parsing();

        assertThatThrownBy(() -> parsing.setMaxTokens(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("l402.parsing.max-tokens")
                .hasMessageContaining(String.valueOf(invalid));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -100, Integer.MIN_VALUE})
    void maxCaveatsRejectsInvalidValues(int invalid) {
        var parsing = new L402Properties.Parsing();

        assertThatThrownBy(() -> parsing.setMaxCaveats(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("l402.parsing.max-caveats")
                .hasMessageContaining(String.valueOf(invalid));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -100, Integer.MIN_VALUE})
    void maxMacaroonBytesRejectsInvalidValues(int invalid) {
        var parsing = new L402Properties.Parsing();

        assertThatThrownBy(() -> parsing.setMaxMacaroonBytes(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("l402.parsing.max-macaroon-bytes")
                .hasMessageContaining(String.valueOf(invalid));
    }

    @Test
    void parsingFieldOnL402Properties() {
        var props = new L402Properties();

        assertThat(props.getParsing()).isNotNull();
        assertThat(props.getParsing().getMaxTokens()).isEqualTo(5);

        var custom = new L402Properties.Parsing();
        custom.setMaxTokens(3);
        props.setParsing(custom);

        assertThat(props.getParsing().getMaxTokens()).isEqualTo(3);
    }
}
