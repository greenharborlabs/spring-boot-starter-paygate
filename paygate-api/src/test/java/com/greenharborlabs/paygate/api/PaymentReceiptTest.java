package com.greenharborlabs.paygate.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentReceiptTest {

    private static final String VALID_STATUS = "success";
    private static final String VALID_CHALLENGE_ID = "chall_abc123";
    private static final String VALID_METHOD = "lightning";
    private static final String VALID_REFERENCE = "lnbc10u1p0test";
    private static final long VALID_AMOUNT = 100L;
    private static final String VALID_TIMESTAMP = "2026-03-21T12:00:00Z";
    private static final String VALID_SCHEME = "Payment";

    private PaymentReceipt validReceipt() {
        return new PaymentReceipt(
                VALID_STATUS, VALID_CHALLENGE_ID, VALID_METHOD,
                VALID_REFERENCE, VALID_AMOUNT, VALID_TIMESTAMP, VALID_SCHEME
        );
    }

    // --- Valid construction ---

    @Test
    void constructWithAllValidFields() {
        var receipt = validReceipt();

        assertThat(receipt.status()).isEqualTo(VALID_STATUS);
        assertThat(receipt.challengeId()).isEqualTo(VALID_CHALLENGE_ID);
        assertThat(receipt.method()).isEqualTo(VALID_METHOD);
        assertThat(receipt.reference()).isEqualTo(VALID_REFERENCE);
        assertThat(receipt.amountSats()).isEqualTo(VALID_AMOUNT);
        assertThat(receipt.timestamp()).isEqualTo(VALID_TIMESTAMP);
        assertThat(receipt.protocolScheme()).isEqualTo(VALID_SCHEME);
    }

    @Test
    void constructWithNullReferenceIsAllowed() {
        var receipt = new PaymentReceipt(
                VALID_STATUS, VALID_CHALLENGE_ID, VALID_METHOD,
                null, VALID_AMOUNT, VALID_TIMESTAMP, VALID_SCHEME
        );

        assertThat(receipt.reference()).isNull();
    }

    // --- Null validation on required fields ---

    @Test
    void constructWithNullStatusThrows() {
        assertThatThrownBy(() -> new PaymentReceipt(
                null, VALID_CHALLENGE_ID, VALID_METHOD,
                VALID_REFERENCE, VALID_AMOUNT, VALID_TIMESTAMP, VALID_SCHEME
        )).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("status");
    }

    @Test
    void constructWithNullChallengeIdThrows() {
        assertThatThrownBy(() -> new PaymentReceipt(
                VALID_STATUS, null, VALID_METHOD,
                VALID_REFERENCE, VALID_AMOUNT, VALID_TIMESTAMP, VALID_SCHEME
        )).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("challengeId");
    }

    @Test
    void constructWithNullMethodThrows() {
        assertThatThrownBy(() -> new PaymentReceipt(
                VALID_STATUS, VALID_CHALLENGE_ID, null,
                VALID_REFERENCE, VALID_AMOUNT, VALID_TIMESTAMP, VALID_SCHEME
        )).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("method");
    }

    @Test
    void constructWithNullTimestampThrows() {
        assertThatThrownBy(() -> new PaymentReceipt(
                VALID_STATUS, VALID_CHALLENGE_ID, VALID_METHOD,
                VALID_REFERENCE, VALID_AMOUNT, null, VALID_SCHEME
        )).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("timestamp");
    }

    @Test
    void constructWithNullProtocolSchemeThrows() {
        assertThatThrownBy(() -> new PaymentReceipt(
                VALID_STATUS, VALID_CHALLENGE_ID, VALID_METHOD,
                VALID_REFERENCE, VALID_AMOUNT, VALID_TIMESTAMP, null
        )).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("protocolScheme");
    }

    // --- amountSats validation ---

    @ParameterizedTest
    @ValueSource(longs = {0, -1, -100, Long.MIN_VALUE})
    void constructWithNonPositiveAmountSatsThrows(long amount) {
        assertThatThrownBy(() -> new PaymentReceipt(
                VALID_STATUS, VALID_CHALLENGE_ID, VALID_METHOD,
                VALID_REFERENCE, amount, VALID_TIMESTAMP, VALID_SCHEME
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amountSats must be > 0");
    }

    @Test
    void constructWithOneSatAmountIsValid() {
        var receipt = new PaymentReceipt(
                VALID_STATUS, VALID_CHALLENGE_ID, VALID_METHOD,
                VALID_REFERENCE, 1L, VALID_TIMESTAMP, VALID_SCHEME
        );
        assertThat(receipt.amountSats()).isEqualTo(1L);
    }

    // --- Round-trip field access ---

    @Test
    void fieldsRoundTripCorrectly() {
        var receipt = new PaymentReceipt(
                "pending", "chall_xyz", "on-chain",
                "txid:abc", 50_000L, "2026-01-01T00:00:00Z", "L402"
        );

        assertThat(receipt.status()).isEqualTo("pending");
        assertThat(receipt.challengeId()).isEqualTo("chall_xyz");
        assertThat(receipt.method()).isEqualTo("on-chain");
        assertThat(receipt.reference()).isEqualTo("txid:abc");
        assertThat(receipt.amountSats()).isEqualTo(50_000L);
        assertThat(receipt.timestamp()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(receipt.protocolScheme()).isEqualTo("L402");
    }
}
