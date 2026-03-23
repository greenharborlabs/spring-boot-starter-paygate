package com.greenharborlabs.paygate.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentCredentialTest {

    private static final byte[] VALID_HASH = new byte[32];
    private static final byte[] VALID_PREIMAGE = new byte[32];
    private static final String VALID_TOKEN_ID = "tok_abc123";
    private static final String VALID_SCHEME = "L402";
    private static final String VALID_SOURCE = "did:key:z6MkhaXgBZDvotD";
    private static final ProtocolMetadata VALID_METADATA = new TestMetadata("test");

    static {
        for (int i = 0; i < 32; i++) {
            VALID_HASH[i] = (byte) (i + 1);
            VALID_PREIMAGE[i] = (byte) (i + 0x20);
        }
    }

    private record TestMetadata(String value) implements ProtocolMetadata {}

    private PaymentCredential validCredential() {
        return new PaymentCredential(
                VALID_HASH.clone(), VALID_PREIMAGE.clone(), VALID_TOKEN_ID,
                VALID_SCHEME, VALID_SOURCE, VALID_METADATA
        );
    }

    // --- Valid construction ---

    @Test
    void constructWithAllValidFields() {
        var cred = validCredential();

        assertThat(cred.paymentHash()).isEqualTo(VALID_HASH);
        assertThat(cred.preimage()).isEqualTo(VALID_PREIMAGE);
        assertThat(cred.tokenId()).isEqualTo(VALID_TOKEN_ID);
        assertThat(cred.sourceProtocolScheme()).isEqualTo(VALID_SCHEME);
        assertThat(cred.source()).isEqualTo(VALID_SOURCE);
        assertThat(cred.metadata()).isEqualTo(VALID_METADATA);
    }

    @Test
    void constructWithNullSourceIsAllowed() {
        var cred = new PaymentCredential(
                VALID_HASH.clone(), VALID_PREIMAGE.clone(), VALID_TOKEN_ID,
                VALID_SCHEME, null, VALID_METADATA
        );

        assertThat(cred.source()).isNull();
    }

    // --- Null validation on required fields ---

    @Test
    void constructWithNullPaymentHashThrows() {
        assertThatThrownBy(() -> new PaymentCredential(
                null, VALID_PREIMAGE.clone(), VALID_TOKEN_ID,
                VALID_SCHEME, VALID_SOURCE, VALID_METADATA
        )).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("paymentHash");
    }

    @Test
    void constructWithNullPreimageThrows() {
        assertThatThrownBy(() -> new PaymentCredential(
                VALID_HASH.clone(), null, VALID_TOKEN_ID,
                VALID_SCHEME, VALID_SOURCE, VALID_METADATA
        )).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("preimage");
    }

    @Test
    void constructWithNullTokenIdThrows() {
        assertThatThrownBy(() -> new PaymentCredential(
                VALID_HASH.clone(), VALID_PREIMAGE.clone(), null,
                VALID_SCHEME, VALID_SOURCE, VALID_METADATA
        )).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("tokenId");
    }

    @Test
    void constructWithNullSourceProtocolSchemeThrows() {
        assertThatThrownBy(() -> new PaymentCredential(
                VALID_HASH.clone(), VALID_PREIMAGE.clone(), VALID_TOKEN_ID,
                null, VALID_SOURCE, VALID_METADATA
        )).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sourceProtocolScheme");
    }

    @Test
    void constructWithNullMetadataThrows() {
        assertThatThrownBy(() -> new PaymentCredential(
                VALID_HASH.clone(), VALID_PREIMAGE.clone(), VALID_TOKEN_ID,
                VALID_SCHEME, VALID_SOURCE, null
        )).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("metadata");
    }

    // --- Defensive copies: paymentHash ---

    @Test
    void constructorMakesDefensiveCopyOfPaymentHash() {
        byte[] hash = VALID_HASH.clone();
        var cred = new PaymentCredential(
                hash, VALID_PREIMAGE.clone(), VALID_TOKEN_ID,
                VALID_SCHEME, VALID_SOURCE, VALID_METADATA
        );

        hash[0] = (byte) 0xFF;

        assertThat(cred.paymentHash()[0]).isEqualTo((byte) 1);
    }

    @Test
    void paymentHashAccessorReturnsDefensiveCopy() {
        var cred = validCredential();

        byte[] first = cred.paymentHash();
        byte[] second = cred.paymentHash();

        assertThat(first).isNotSameAs(second);
        assertThat(first).isEqualTo(second);
    }

    @Test
    void mutatingReturnedPaymentHashDoesNotAffectRecord() {
        var cred = validCredential();

        byte[] leaked = cred.paymentHash();
        leaked[0] = (byte) 0xFF;

        assertThat(cred.paymentHash()[0]).isEqualTo((byte) 1);
    }

    // --- Defensive copies: preimage ---

    @Test
    void constructorMakesDefensiveCopyOfPreimage() {
        byte[] preimage = VALID_PREIMAGE.clone();
        var cred = new PaymentCredential(
                VALID_HASH.clone(), preimage, VALID_TOKEN_ID,
                VALID_SCHEME, VALID_SOURCE, VALID_METADATA
        );

        preimage[0] = (byte) 0xFF;

        assertThat(cred.preimage()[0]).isEqualTo((byte) 0x20);
    }

    @Test
    void preimageAccessorReturnsDefensiveCopy() {
        var cred = validCredential();

        byte[] first = cred.preimage();
        byte[] second = cred.preimage();

        assertThat(first).isNotSameAs(second);
        assertThat(first).isEqualTo(second);
    }

    @Test
    void mutatingReturnedPreimageDoesNotAffectRecord() {
        var cred = validCredential();

        byte[] leaked = cred.preimage();
        leaked[0] = (byte) 0xFF;

        assertThat(cred.preimage()[0]).isEqualTo((byte) 0x20);
    }

    // --- toString safety ---

    @Test
    void toStringDoesNotLeakSecrets() {
        var cred = validCredential();
        String str = cred.toString();

        assertThat(str).doesNotContain("preimage");
        assertThat(str).doesNotContain("paymentHash");
        assertThat(str).contains("tokenId=" + VALID_TOKEN_ID);
        assertThat(str).contains("sourceProtocolScheme=" + VALID_SCHEME);
        assertThat(str).contains("source=" + VALID_SOURCE);
    }

    // --- equals and hashCode ---

    @Test
    void equalsWithSameInstance() {
        var cred = validCredential();
        assertThat(cred).isEqualTo(cred);
    }

    @Test
    void equalsWithEqualContent() {
        var cred1 = validCredential();
        var cred2 = validCredential();
        assertThat(cred1).isEqualTo(cred2);
        assertThat(cred1.hashCode()).isEqualTo(cred2.hashCode());
    }

    @Test
    void notEqualWithDifferentPaymentHash() {
        var cred1 = validCredential();
        byte[] differentHash = new byte[32];
        differentHash[0] = (byte) 0xFF;
        var cred2 = new PaymentCredential(
                differentHash, VALID_PREIMAGE.clone(), VALID_TOKEN_ID,
                VALID_SCHEME, VALID_SOURCE, VALID_METADATA
        );
        assertThat(cred1).isNotEqualTo(cred2);
    }

    @Test
    void notEqualWithDifferentPreimage() {
        var cred1 = validCredential();
        byte[] differentPreimage = new byte[32];
        differentPreimage[0] = (byte) 0xFF;
        var cred2 = new PaymentCredential(
                VALID_HASH.clone(), differentPreimage, VALID_TOKEN_ID,
                VALID_SCHEME, VALID_SOURCE, VALID_METADATA
        );
        assertThat(cred1).isNotEqualTo(cred2);
    }

    @Test
    void notEqualWithDifferentTokenId() {
        var cred1 = validCredential();
        var cred2 = new PaymentCredential(
                VALID_HASH.clone(), VALID_PREIMAGE.clone(), "different-token",
                VALID_SCHEME, VALID_SOURCE, VALID_METADATA
        );
        assertThat(cred1).isNotEqualTo(cred2);
    }

    @Test
    void notEqualToNull() {
        var cred = validCredential();
        assertThat(cred).isNotEqualTo(null);
    }

    @Test
    void notEqualToDifferentType() {
        var cred = validCredential();
        assertThat(cred).isNotEqualTo("not a credential");
    }

    // --- Round-trip field access ---

    @Test
    void allFieldsRoundTripCorrectly() {
        var metadata = new TestMetadata("round-trip");
        var cred = new PaymentCredential(
                VALID_HASH.clone(), VALID_PREIMAGE.clone(), "token-rt",
                "Payment", "did:key:z6Mk123", metadata
        );

        assertThat(cred.tokenId()).isEqualTo("token-rt");
        assertThat(cred.sourceProtocolScheme()).isEqualTo("Payment");
        assertThat(cred.source()).isEqualTo("did:key:z6Mk123");
        assertThat(cred.metadata()).isEqualTo(metadata);
    }
}
