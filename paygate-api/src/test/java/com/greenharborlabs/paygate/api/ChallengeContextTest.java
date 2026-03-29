package com.greenharborlabs.paygate.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ChallengeContextTest {

  private static final byte[] VALID_HASH = new byte[32];
  private static final byte[] VALID_ROOT_KEY = new byte[32];
  private static final String VALID_TOKEN_ID = "tok_abc123";
  private static final String VALID_BOLT11 = "lnbc10u1p0test";
  private static final long VALID_PRICE = 100L;
  private static final String VALID_DESCRIPTION = "test payment";
  private static final String VALID_SERVICE = "my-service";
  private static final long VALID_TIMEOUT = 3600L;
  private static final String VALID_CAPABILITY = "read";
  private static final String VALID_DIGEST = "sha256:abc";

  static {
    for (int i = 0; i < 32; i++) {
      VALID_HASH[i] = (byte) (i + 1);
      VALID_ROOT_KEY[i] = (byte) (i + 0x40);
    }
  }

  private ChallengeContext validContext() {
    return new ChallengeContext(
        VALID_HASH.clone(),
        VALID_TOKEN_ID,
        VALID_BOLT11,
        VALID_PRICE,
        VALID_DESCRIPTION,
        VALID_SERVICE,
        VALID_TIMEOUT,
        VALID_CAPABILITY,
        VALID_ROOT_KEY.clone(),
        Map.of("key", "value"),
        VALID_DIGEST);
  }

  // --- Valid construction ---

  @Test
  void constructWithAllValidFields() {
    var ctx = validContext();

    assertThat(ctx.paymentHash()).isEqualTo(VALID_HASH);
    assertThat(ctx.tokenId()).isEqualTo(VALID_TOKEN_ID);
    assertThat(ctx.bolt11Invoice()).isEqualTo(VALID_BOLT11);
    assertThat(ctx.priceSats()).isEqualTo(VALID_PRICE);
    assertThat(ctx.description()).isEqualTo(VALID_DESCRIPTION);
    assertThat(ctx.serviceName()).isEqualTo(VALID_SERVICE);
    assertThat(ctx.timeoutSeconds()).isEqualTo(VALID_TIMEOUT);
    assertThat(ctx.capability()).isEqualTo(VALID_CAPABILITY);
    assertThat(ctx.rootKeyBytes()).isEqualTo(VALID_ROOT_KEY);
    assertThat(ctx.opaque()).containsEntry("key", "value");
    assertThat(ctx.digest()).isEqualTo(VALID_DIGEST);
  }

  @Test
  void constructWithNullOptionalFieldsIsAllowed() {
    var ctx =
        new ChallengeContext(
            VALID_HASH.clone(),
            VALID_TOKEN_ID,
            VALID_BOLT11,
            VALID_PRICE,
            null,
            null,
            VALID_TIMEOUT,
            null,
            null,
            null,
            null);

    assertThat(ctx.description()).isNull();
    assertThat(ctx.serviceName()).isNull();
    assertThat(ctx.capability()).isNull();
    assertThat(ctx.rootKeyBytes()).isNull();
    assertThat(ctx.opaque()).isNull();
    assertThat(ctx.digest()).isNull();
  }

  // --- Null validation on required fields ---

  @Test
  void constructWithNullPaymentHashThrows() {
    assertThatThrownBy(
            () ->
                new ChallengeContext(
                    null,
                    VALID_TOKEN_ID,
                    VALID_BOLT11,
                    VALID_PRICE,
                    null,
                    null,
                    VALID_TIMEOUT,
                    null,
                    null,
                    null,
                    null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("paymentHash");
  }

  @Test
  void constructWithNullTokenIdThrows() {
    assertThatThrownBy(
            () ->
                new ChallengeContext(
                    VALID_HASH.clone(),
                    null,
                    VALID_BOLT11,
                    VALID_PRICE,
                    null,
                    null,
                    VALID_TIMEOUT,
                    null,
                    null,
                    null,
                    null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("tokenId");
  }

  @Test
  void constructWithNullBolt11InvoiceThrows() {
    assertThatThrownBy(
            () ->
                new ChallengeContext(
                    VALID_HASH.clone(),
                    VALID_TOKEN_ID,
                    null,
                    VALID_PRICE,
                    null,
                    null,
                    VALID_TIMEOUT,
                    null,
                    null,
                    null,
                    null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("bolt11Invoice");
  }

  // --- priceSats validation ---

  @ParameterizedTest
  @ValueSource(longs = {0, -1, -100, Long.MIN_VALUE})
  void constructWithNonPositivePriceSatsThrows(long price) {
    assertThatThrownBy(
            () ->
                new ChallengeContext(
                    VALID_HASH.clone(),
                    VALID_TOKEN_ID,
                    VALID_BOLT11,
                    price,
                    null,
                    null,
                    VALID_TIMEOUT,
                    null,
                    null,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("priceSats must be positive");
  }

  @Test
  void constructWithOneSatPriceIsValid() {
    var ctx =
        new ChallengeContext(
            VALID_HASH.clone(),
            VALID_TOKEN_ID,
            VALID_BOLT11,
            1L,
            null,
            null,
            VALID_TIMEOUT,
            null,
            null,
            null,
            null);
    assertThat(ctx.priceSats()).isEqualTo(1L);
  }

  // --- Defensive copies: paymentHash ---

  @Test
  void constructorMakesDefensiveCopyOfPaymentHash() {
    byte[] hash = VALID_HASH.clone();
    var ctx =
        new ChallengeContext(
            hash,
            VALID_TOKEN_ID,
            VALID_BOLT11,
            VALID_PRICE,
            null,
            null,
            VALID_TIMEOUT,
            null,
            null,
            null,
            null);

    hash[0] = (byte) 0xFF;

    assertThat(ctx.paymentHash()[0]).isEqualTo((byte) 1);
  }

  @Test
  void paymentHashAccessorReturnsDefensiveCopy() {
    var ctx = validContext();

    byte[] first = ctx.paymentHash();
    byte[] second = ctx.paymentHash();

    assertThat(first).isNotSameAs(second);
    assertThat(first).isEqualTo(second);
  }

  @Test
  void mutatingReturnedPaymentHashDoesNotAffectRecord() {
    var ctx = validContext();

    byte[] leaked = ctx.paymentHash();
    leaked[0] = (byte) 0xFF;

    assertThat(ctx.paymentHash()[0]).isEqualTo((byte) 1);
  }

  // --- Defensive copies: rootKeyBytes ---

  @Test
  void constructorMakesDefensiveCopyOfRootKeyBytes() {
    byte[] rootKey = VALID_ROOT_KEY.clone();
    var ctx =
        new ChallengeContext(
            VALID_HASH.clone(),
            VALID_TOKEN_ID,
            VALID_BOLT11,
            VALID_PRICE,
            null,
            null,
            VALID_TIMEOUT,
            null,
            rootKey,
            null,
            null);

    rootKey[0] = (byte) 0xFF;

    assertThat(ctx.rootKeyBytes()[0]).isEqualTo((byte) 0x40);
  }

  @Test
  void rootKeyBytesAccessorReturnsDefensiveCopy() {
    var ctx = validContext();

    byte[] first = ctx.rootKeyBytes();
    byte[] second = ctx.rootKeyBytes();

    assertThat(first).isNotSameAs(second);
    assertThat(first).isEqualTo(second);
  }

  @Test
  void mutatingReturnedRootKeyBytesDoesNotAffectRecord() {
    var ctx = validContext();

    byte[] leaked = ctx.rootKeyBytes();
    leaked[0] = (byte) 0xFF;

    assertThat(ctx.rootKeyBytes()[0]).isEqualTo((byte) 0x40);
  }

  @Test
  void rootKeyBytesAccessorReturnsNullWhenNull() {
    var ctx =
        new ChallengeContext(
            VALID_HASH.clone(),
            VALID_TOKEN_ID,
            VALID_BOLT11,
            VALID_PRICE,
            null,
            null,
            VALID_TIMEOUT,
            null,
            null,
            null,
            null);

    assertThat(ctx.rootKeyBytes()).isNull();
  }

  // --- Opaque map immutability ---

  @Test
  void opaqueMapIsImmutableAfterConstruction() {
    var mutableMap = new HashMap<String, String>();
    mutableMap.put("key1", "value1");

    var ctx =
        new ChallengeContext(
            VALID_HASH.clone(),
            VALID_TOKEN_ID,
            VALID_BOLT11,
            VALID_PRICE,
            null,
            null,
            VALID_TIMEOUT,
            null,
            null,
            mutableMap,
            null);

    assertThatThrownBy(() -> ctx.opaque().put("key2", "value2"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void opaqueMapIsDefensivelyCopiedFromInput() {
    var mutableMap = new HashMap<String, String>();
    mutableMap.put("key1", "value1");

    var ctx =
        new ChallengeContext(
            VALID_HASH.clone(),
            VALID_TOKEN_ID,
            VALID_BOLT11,
            VALID_PRICE,
            null,
            null,
            VALID_TIMEOUT,
            null,
            null,
            mutableMap,
            null);

    mutableMap.put("key2", "value2");

    assertThat(ctx.opaque()).hasSize(1);
    assertThat(ctx.opaque()).containsOnlyKeys("key1");
  }

  @Test
  void opaqueMapNullIsAllowed() {
    var ctx =
        new ChallengeContext(
            VALID_HASH.clone(),
            VALID_TOKEN_ID,
            VALID_BOLT11,
            VALID_PRICE,
            null,
            null,
            VALID_TIMEOUT,
            null,
            null,
            null,
            null);

    assertThat(ctx.opaque()).isNull();
  }

  // --- equals / hashCode ---

  @Test
  void equalsReturnsTrueForIdenticalContexts() {
    var a = validContext();
    var b = validContext();

    assertThat(a).isEqualTo(b);
    assertThat(a.hashCode()).isEqualTo(b.hashCode());
  }

  @Test
  void equalsReturnsTrueForSameInstance() {
    var a = validContext();
    assertThat(a).isEqualTo(a);
  }

  @Test
  void equalsReturnsFalseForNull() {
    assertThat(validContext()).isNotEqualTo(null);
  }

  @Test
  void equalsReturnsFalseForDifferentType() {
    assertThat(validContext()).isNotEqualTo("not a context");
  }

  @Test
  void equalsReturnsFalseWhenPaymentHashDiffers() {
    var a = validContext();
    byte[] differentHash = VALID_HASH.clone();
    differentHash[0] = (byte) 0xFF;
    var b =
        new ChallengeContext(
            differentHash,
            VALID_TOKEN_ID,
            VALID_BOLT11,
            VALID_PRICE,
            VALID_DESCRIPTION,
            VALID_SERVICE,
            VALID_TIMEOUT,
            VALID_CAPABILITY,
            VALID_ROOT_KEY.clone(),
            Map.of("key", "value"),
            VALID_DIGEST);

    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void equalsReturnsFalseWhenTokenIdDiffers() {
    var a = validContext();
    var b =
        new ChallengeContext(
            VALID_HASH.clone(),
            "different-token",
            VALID_BOLT11,
            VALID_PRICE,
            VALID_DESCRIPTION,
            VALID_SERVICE,
            VALID_TIMEOUT,
            VALID_CAPABILITY,
            VALID_ROOT_KEY.clone(),
            Map.of("key", "value"),
            VALID_DIGEST);

    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void equalsReturnsFalseWhenPriceSatsDiffers() {
    var a = validContext();
    var b =
        new ChallengeContext(
            VALID_HASH.clone(),
            VALID_TOKEN_ID,
            VALID_BOLT11,
            999L,
            VALID_DESCRIPTION,
            VALID_SERVICE,
            VALID_TIMEOUT,
            VALID_CAPABILITY,
            VALID_ROOT_KEY.clone(),
            Map.of("key", "value"),
            VALID_DIGEST);

    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void equalsReturnsFalseWhenRootKeyBytesDiffers() {
    var a = validContext();
    byte[] differentKey = VALID_ROOT_KEY.clone();
    differentKey[0] = (byte) 0xFF;
    var b =
        new ChallengeContext(
            VALID_HASH.clone(),
            VALID_TOKEN_ID,
            VALID_BOLT11,
            VALID_PRICE,
            VALID_DESCRIPTION,
            VALID_SERVICE,
            VALID_TIMEOUT,
            VALID_CAPABILITY,
            differentKey,
            Map.of("key", "value"),
            VALID_DIGEST);

    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void equalsHandlesNullRootKeyBytesOnBothSides() {
    var a =
        new ChallengeContext(
            VALID_HASH.clone(),
            VALID_TOKEN_ID,
            VALID_BOLT11,
            VALID_PRICE,
            null,
            null,
            VALID_TIMEOUT,
            null,
            null,
            null,
            null);
    var b =
        new ChallengeContext(
            VALID_HASH.clone(),
            VALID_TOKEN_ID,
            VALID_BOLT11,
            VALID_PRICE,
            null,
            null,
            VALID_TIMEOUT,
            null,
            null,
            null,
            null);

    assertThat(a).isEqualTo(b);
    assertThat(a.hashCode()).isEqualTo(b.hashCode());
  }

  // --- toString ---

  @Test
  void toStringContainsTokenIdPriceAndServiceName() {
    var ctx = validContext();
    String str = ctx.toString();

    assertThat(str).contains(VALID_TOKEN_ID);
    assertThat(str).contains(String.valueOf(VALID_PRICE));
    assertThat(str).contains(VALID_SERVICE);
  }

  @Test
  void toStringDoesNotLeakSensitiveBytes() {
    var ctx = validContext();
    String str = ctx.toString();

    // toString should not contain raw byte array representations or rootKeyBytes
    assertThat(str).doesNotContain("rootKeyBytes");
    assertThat(str).doesNotContain("paymentHash");
  }
}
