package com.greenharborlabs.paygate.core.lightning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InvoiceTest {

  private static final byte[] VALID_HASH = new byte[32];
  private static final byte[] VALID_PREIMAGE = new byte[32];
  private static final String VALID_BOLT11 = "lnbc10u1p0abcdef";
  private static final long VALID_AMOUNT = 100L;
  private static final String VALID_MEMO = "test payment";
  private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant EXPIRES = Instant.parse("2026-01-01T01:00:00Z");

  static {
    for (int i = 0; i < 32; i++) {
      VALID_HASH[i] = (byte) (i + 1);
      VALID_PREIMAGE[i] = (byte) (i + 0x20);
    }
  }

  private Invoice validInvoice() {
    return new Invoice(
        VALID_HASH.clone(),
        VALID_BOLT11,
        VALID_AMOUNT,
        VALID_MEMO,
        InvoiceStatus.PENDING,
        VALID_PREIMAGE.clone(),
        CREATED,
        EXPIRES);
  }

  // --- Valid construction ---

  @Test
  void constructWithAllValidFields() {
    var invoice = validInvoice();

    assertThat(invoice.paymentHash()).isEqualTo(VALID_HASH);
    assertThat(invoice.bolt11()).isEqualTo(VALID_BOLT11);
    assertThat(invoice.amountSats()).isEqualTo(VALID_AMOUNT);
    assertThat(invoice.memo()).isEqualTo(VALID_MEMO);
    assertThat(invoice.status()).isEqualTo(InvoiceStatus.PENDING);
    assertThat(invoice.preimage()).isEqualTo(VALID_PREIMAGE);
    assertThat(invoice.createdAt()).isEqualTo(CREATED);
    assertThat(invoice.expiresAt()).isEqualTo(EXPIRES);
  }

  @Test
  void constructWithNullMemoIsAllowed() {
    var invoice =
        new Invoice(
            VALID_HASH.clone(),
            VALID_BOLT11,
            VALID_AMOUNT,
            null,
            InvoiceStatus.PENDING,
            null,
            CREATED,
            EXPIRES);

    assertThat(invoice.memo()).isNull();
  }

  @Test
  void constructWithNullPreimageIsAllowed() {
    var invoice =
        new Invoice(
            VALID_HASH.clone(),
            VALID_BOLT11,
            VALID_AMOUNT,
            VALID_MEMO,
            InvoiceStatus.SETTLED,
            null,
            CREATED,
            EXPIRES);

    assertThat(invoice.preimage()).isNull();
  }

  // --- paymentHash validation ---

  @Test
  void constructWithNullPaymentHashThrows() {
    assertThatThrownBy(
            () ->
                new Invoice(
                    null,
                    VALID_BOLT11,
                    VALID_AMOUNT,
                    VALID_MEMO,
                    InvoiceStatus.PENDING,
                    null,
                    CREATED,
                    EXPIRES))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("paymentHash must not be null");
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 16, 31, 33, 64})
  void constructWithWrongSizePaymentHashThrows(int size) {
    assertThatThrownBy(
            () ->
                new Invoice(
                    new byte[size],
                    VALID_BOLT11,
                    VALID_AMOUNT,
                    VALID_MEMO,
                    InvoiceStatus.PENDING,
                    null,
                    CREATED,
                    EXPIRES))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("paymentHash must be exactly 32 bytes");
  }

  // --- bolt11 validation ---

  @Test
  void constructWithNullBolt11Throws() {
    assertThatThrownBy(
            () ->
                new Invoice(
                    VALID_HASH.clone(),
                    null,
                    VALID_AMOUNT,
                    VALID_MEMO,
                    InvoiceStatus.PENDING,
                    null,
                    CREATED,
                    EXPIRES))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bolt11 must not be null or empty");
  }

  @Test
  void constructWithEmptyBolt11Throws() {
    assertThatThrownBy(
            () ->
                new Invoice(
                    VALID_HASH.clone(),
                    "",
                    VALID_AMOUNT,
                    VALID_MEMO,
                    InvoiceStatus.PENDING,
                    null,
                    CREATED,
                    EXPIRES))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bolt11 must not be null or empty");
  }

  // --- amountSats validation ---

  @ParameterizedTest
  @ValueSource(longs = {0, -1, -100, Long.MIN_VALUE})
  void constructWithNonPositiveAmountThrows(long amount) {
    assertThatThrownBy(
            () ->
                new Invoice(
                    VALID_HASH.clone(),
                    VALID_BOLT11,
                    amount,
                    VALID_MEMO,
                    InvoiceStatus.PENDING,
                    null,
                    CREATED,
                    EXPIRES))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("amountSats must be > 0");
  }

  @Test
  void constructWithOneStaIsValid() {
    var invoice =
        new Invoice(
            VALID_HASH.clone(),
            VALID_BOLT11,
            1L,
            VALID_MEMO,
            InvoiceStatus.PENDING,
            null,
            CREATED,
            EXPIRES);
    assertThat(invoice.amountSats()).isEqualTo(1L);
  }

  // --- status validation ---

  @Test
  void constructWithNullStatusThrows() {
    assertThatThrownBy(
            () ->
                new Invoice(
                    VALID_HASH.clone(),
                    VALID_BOLT11,
                    VALID_AMOUNT,
                    VALID_MEMO,
                    null,
                    null,
                    CREATED,
                    EXPIRES))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("status must not be null");
  }

  // --- createdAt / expiresAt validation ---

  @Test
  void constructWithNullCreatedAtThrows() {
    assertThatThrownBy(
            () ->
                new Invoice(
                    VALID_HASH.clone(),
                    VALID_BOLT11,
                    VALID_AMOUNT,
                    VALID_MEMO,
                    InvoiceStatus.PENDING,
                    null,
                    null,
                    EXPIRES))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("createdAt must not be null");
  }

  @Test
  void constructWithNullExpiresAtThrows() {
    assertThatThrownBy(
            () ->
                new Invoice(
                    VALID_HASH.clone(),
                    VALID_BOLT11,
                    VALID_AMOUNT,
                    VALID_MEMO,
                    InvoiceStatus.PENDING,
                    null,
                    CREATED,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("expiresAt must not be null");
  }

  // --- preimage validation ---

  @ParameterizedTest
  @ValueSource(ints = {1, 16, 31, 33, 64})
  void constructWithWrongSizePreimageThrows(int size) {
    assertThatThrownBy(
            () ->
                new Invoice(
                    VALID_HASH.clone(),
                    VALID_BOLT11,
                    VALID_AMOUNT,
                    VALID_MEMO,
                    InvoiceStatus.PENDING,
                    new byte[size],
                    CREATED,
                    EXPIRES))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("preimage must be exactly 32 bytes");
  }

  // --- Defensive copies ---

  @Test
  void constructorMakesDefensiveCopyOfPaymentHash() {
    byte[] hash = VALID_HASH.clone();
    var invoice =
        new Invoice(
            hash,
            VALID_BOLT11,
            VALID_AMOUNT,
            VALID_MEMO,
            InvoiceStatus.PENDING,
            null,
            CREATED,
            EXPIRES);

    hash[0] = (byte) 0xFF;

    assertThat(invoice.paymentHash()[0]).isEqualTo((byte) 1);
  }

  @Test
  void constructorMakesDefensiveCopyOfPreimage() {
    byte[] preimage = VALID_PREIMAGE.clone();
    var invoice =
        new Invoice(
            VALID_HASH.clone(),
            VALID_BOLT11,
            VALID_AMOUNT,
            VALID_MEMO,
            InvoiceStatus.PENDING,
            preimage,
            CREATED,
            EXPIRES);

    preimage[0] = (byte) 0xFF;

    assertThat(invoice.preimage()[0]).isEqualTo((byte) 0x20);
  }

  @Test
  void paymentHashAccessorReturnsDefensiveCopy() {
    var invoice = validInvoice();

    byte[] first = invoice.paymentHash();
    byte[] second = invoice.paymentHash();

    assertThat(first).isNotSameAs(second);
    assertThat(first).isEqualTo(second);
  }

  @Test
  void preimageAccessorReturnsDefensiveCopy() {
    var invoice = validInvoice();

    byte[] first = invoice.preimage();
    byte[] second = invoice.preimage();

    assertThat(first).isNotSameAs(second);
    assertThat(first).isEqualTo(second);
  }

  @Test
  void mutatingReturnedPaymentHashDoesNotAffectInvoice() {
    var invoice = validInvoice();

    byte[] leaked = invoice.paymentHash();
    leaked[0] = (byte) 0xFF;

    assertThat(invoice.paymentHash()[0]).isEqualTo((byte) 1);
  }

  @Test
  void mutatingReturnedPreimageDoesNotAffectInvoice() {
    var invoice = validInvoice();

    byte[] leaked = invoice.preimage();
    leaked[0] = (byte) 0xFF;

    assertThat(invoice.preimage()[0]).isEqualTo((byte) 0x20);
  }

  @Test
  void preimageAccessorReturnsNullWhenPreimageIsNull() {
    var invoice =
        new Invoice(
            VALID_HASH.clone(),
            VALID_BOLT11,
            VALID_AMOUNT,
            VALID_MEMO,
            InvoiceStatus.PENDING,
            null,
            CREATED,
            EXPIRES);

    assertThat(invoice.preimage()).isNull();
  }

  // --- equals / hashCode ---

  @Test
  void equalsReturnsTrueForIdenticalInvoices() {
    var a = validInvoice();
    var b = validInvoice();

    assertThat(a).isEqualTo(b);
    assertThat(a.hashCode()).isEqualTo(b.hashCode());
  }

  @Test
  void equalsReturnsTrueForSameInstance() {
    var a = validInvoice();
    assertThat(a).isEqualTo(a);
  }

  @Test
  void equalsReturnsFalseForNull() {
    assertThat(validInvoice()).isNotEqualTo(null);
  }

  @Test
  void equalsReturnsFalseForDifferentType() {
    assertThat(validInvoice()).isNotEqualTo("not an invoice");
  }

  @Test
  void equalsReturnsFalseWhenPaymentHashDiffers() {
    var a = validInvoice();
    byte[] differentHash = VALID_HASH.clone();
    differentHash[0] = (byte) 0xFF;
    var b =
        new Invoice(
            differentHash,
            VALID_BOLT11,
            VALID_AMOUNT,
            VALID_MEMO,
            InvoiceStatus.PENDING,
            VALID_PREIMAGE.clone(),
            CREATED,
            EXPIRES);

    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void equalsReturnsFalseWhenBolt11Differs() {
    var a = validInvoice();
    var b =
        new Invoice(
            VALID_HASH.clone(),
            "lnbc999different",
            VALID_AMOUNT,
            VALID_MEMO,
            InvoiceStatus.PENDING,
            VALID_PREIMAGE.clone(),
            CREATED,
            EXPIRES);

    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void equalsReturnsFalseWhenAmountDiffers() {
    var a = validInvoice();
    var b =
        new Invoice(
            VALID_HASH.clone(),
            VALID_BOLT11,
            999L,
            VALID_MEMO,
            InvoiceStatus.PENDING,
            VALID_PREIMAGE.clone(),
            CREATED,
            EXPIRES);

    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void equalsReturnsFalseWhenMemoDiffers() {
    var a = validInvoice();
    var b =
        new Invoice(
            VALID_HASH.clone(),
            VALID_BOLT11,
            VALID_AMOUNT,
            "different memo",
            InvoiceStatus.PENDING,
            VALID_PREIMAGE.clone(),
            CREATED,
            EXPIRES);

    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void equalsHandlesNullMemoOnBothSides() {
    var a =
        new Invoice(
            VALID_HASH.clone(),
            VALID_BOLT11,
            VALID_AMOUNT,
            null,
            InvoiceStatus.PENDING,
            null,
            CREATED,
            EXPIRES);
    var b =
        new Invoice(
            VALID_HASH.clone(),
            VALID_BOLT11,
            VALID_AMOUNT,
            null,
            InvoiceStatus.PENDING,
            null,
            CREATED,
            EXPIRES);

    assertThat(a).isEqualTo(b);
    assertThat(a.hashCode()).isEqualTo(b.hashCode());
  }

  @Test
  void equalsReturnsFalseWhenOneMemoIsNull() {
    var a =
        new Invoice(
            VALID_HASH.clone(),
            VALID_BOLT11,
            VALID_AMOUNT,
            null,
            InvoiceStatus.PENDING,
            null,
            CREATED,
            EXPIRES);
    var b =
        new Invoice(
            VALID_HASH.clone(),
            VALID_BOLT11,
            VALID_AMOUNT,
            "some memo",
            InvoiceStatus.PENDING,
            null,
            CREATED,
            EXPIRES);

    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void equalsReturnsFalseWhenStatusDiffers() {
    var a = validInvoice();
    var b =
        new Invoice(
            VALID_HASH.clone(),
            VALID_BOLT11,
            VALID_AMOUNT,
            VALID_MEMO,
            InvoiceStatus.SETTLED,
            VALID_PREIMAGE.clone(),
            CREATED,
            EXPIRES);

    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void equalsReturnsFalseWhenPreimageDiffers() {
    var a = validInvoice();
    byte[] differentPreimage = new byte[32];
    Arrays.fill(differentPreimage, (byte) 0xAB);
    var b =
        new Invoice(
            VALID_HASH.clone(),
            VALID_BOLT11,
            VALID_AMOUNT,
            VALID_MEMO,
            InvoiceStatus.PENDING,
            differentPreimage,
            CREATED,
            EXPIRES);

    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void equalsReturnsFalseWhenPreimageNullVsNonNull() {
    var a =
        new Invoice(
            VALID_HASH.clone(),
            VALID_BOLT11,
            VALID_AMOUNT,
            VALID_MEMO,
            InvoiceStatus.PENDING,
            null,
            CREATED,
            EXPIRES);
    var b = validInvoice();

    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void equalsReturnsFalseWhenCreatedAtDiffers() {
    var a = validInvoice();
    var b =
        new Invoice(
            VALID_HASH.clone(),
            VALID_BOLT11,
            VALID_AMOUNT,
            VALID_MEMO,
            InvoiceStatus.PENDING,
            VALID_PREIMAGE.clone(),
            Instant.parse("2025-06-01T00:00:00Z"),
            EXPIRES);

    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void equalsReturnsFalseWhenExpiresAtDiffers() {
    var a = validInvoice();
    var b =
        new Invoice(
            VALID_HASH.clone(),
            VALID_BOLT11,
            VALID_AMOUNT,
            VALID_MEMO,
            InvoiceStatus.PENDING,
            VALID_PREIMAGE.clone(),
            CREATED,
            Instant.parse("2027-12-31T23:59:59Z"));

    assertThat(a).isNotEqualTo(b);
  }

  // --- Constant-time comparison security property ---

  @Test
  void equalsUsesConstantTimeComparisonForSecretFields() {
    // This test documents the security property that equals() uses constant-time
    // comparison (via MacaroonCrypto.constantTimeEquals) for paymentHash and preimage,
    // preventing timing side-channel attacks. We verify behavioral correctness here;
    // the constant-time guarantee comes from the implementation using XOR accumulation.

    var base = validInvoice();

    // Identical secrets -- must be equal
    var identical = validInvoice();
    assertThat(base).isEqualTo(identical);

    // paymentHash differs only in last byte -- must be not-equal
    byte[] nearHash = VALID_HASH.clone();
    nearHash[31] ^= 0x01;
    var nearHashInvoice =
        new Invoice(
            nearHash,
            VALID_BOLT11,
            VALID_AMOUNT,
            VALID_MEMO,
            InvoiceStatus.PENDING,
            VALID_PREIMAGE.clone(),
            CREATED,
            EXPIRES);
    assertThat(base).isNotEqualTo(nearHashInvoice);

    // preimage differs only in last byte -- must be not-equal
    byte[] nearPreimage = VALID_PREIMAGE.clone();
    nearPreimage[31] ^= 0x01;
    var nearPreimageInvoice =
        new Invoice(
            VALID_HASH.clone(),
            VALID_BOLT11,
            VALID_AMOUNT,
            VALID_MEMO,
            InvoiceStatus.PENDING,
            nearPreimage,
            CREATED,
            EXPIRES);
    assertThat(base).isNotEqualTo(nearPreimageInvoice);

    // Both preimages null -- must be equal
    var nullPreA =
        new Invoice(
            VALID_HASH.clone(),
            VALID_BOLT11,
            VALID_AMOUNT,
            VALID_MEMO,
            InvoiceStatus.PENDING,
            null,
            CREATED,
            EXPIRES);
    var nullPreB =
        new Invoice(
            VALID_HASH.clone(),
            VALID_BOLT11,
            VALID_AMOUNT,
            VALID_MEMO,
            InvoiceStatus.PENDING,
            null,
            CREATED,
            EXPIRES);
    assertThat(nullPreA).isEqualTo(nullPreB);

    // One preimage null, other non-null -- must be not-equal
    assertThat(nullPreA).isNotEqualTo(base);
    assertThat(base).isNotEqualTo(nullPreA);
  }

  // --- toString ---

  @Test
  void toStringContainsBolt11AmountAndStatus() {
    var invoice = validInvoice();
    String str = invoice.toString();

    assertThat(str).contains(VALID_BOLT11);
    assertThat(str).contains(String.valueOf(VALID_AMOUNT));
    assertThat(str).contains("PENDING");
  }

  @Test
  void toStringDoesNotLeakPaymentHashOrPreimage() {
    var invoice = validInvoice();
    String str = invoice.toString();

    // toString should NOT contain raw byte array dumps of sensitive data
    // It should only contain bolt11, amountSats, and status
    assertThat(str).doesNotContain("paymentHash");
    assertThat(str).doesNotContain("preimage");
  }

  // --- All statuses can be used ---

  @Test
  void constructWithEachInvoiceStatus() {
    for (InvoiceStatus status : InvoiceStatus.values()) {
      var invoice =
          new Invoice(
              VALID_HASH.clone(),
              VALID_BOLT11,
              VALID_AMOUNT,
              VALID_MEMO,
              status,
              null,
              CREATED,
              EXPIRES);
      assertThat(invoice.status()).isEqualTo(status);
    }
  }
}
