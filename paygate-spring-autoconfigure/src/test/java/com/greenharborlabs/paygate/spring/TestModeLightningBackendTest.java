package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.greenharborlabs.paygate.core.lightning.InvoiceStatus;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TestModeLightningBackend")
class TestModeLightningBackendTest {

  private TestModeLightningBackend backend;

  @BeforeEach
  void setUp() {
    backend = new TestModeLightningBackend();
  }

  @Test
  @DisplayName("createInvoice returns PENDING invoice with valid preimage/paymentHash pair")
  void createInvoiceReturnsPendingWithValidPreimage() throws NoSuchAlgorithmException {
    var invoice = backend.createInvoice(250, "unit test");

    assertThat(invoice.paymentHash()).hasSize(32);
    assertThat(invoice.bolt11()).startsWith("lntb250test");
    assertThat(invoice.amountSats()).isEqualTo(250);
    assertThat(invoice.memo()).isEqualTo("unit test");
    assertThat(invoice.status()).isEqualTo(InvoiceStatus.PENDING);
    assertThat(invoice.preimage()).hasSize(32);
    assertThat(invoice.createdAt()).isNotNull();
    assertThat(invoice.expiresAt()).isAfter(invoice.createdAt());

    // paymentHash must equal SHA-256(preimage)
    byte[] expectedHash = MessageDigest.getInstance("SHA-256").digest(invoice.preimage());
    assertThat(invoice.paymentHash()).isEqualTo(expectedHash);
  }

  @Test
  @DisplayName("createInvoice generates unique payment hashes on successive calls")
  void createInvoiceGeneratesUniqueHashes() {
    var first = backend.createInvoice(100, "a");
    var second = backend.createInvoice(100, "b");

    assertThat(first.paymentHash()).isNotEqualTo(second.paymentHash());
  }

  @Test
  @DisplayName("lookupInvoice returns SETTLED with the correct preimage for created invoices")
  void lookupInvoiceReturnsSettledWithCorrectPreimage() {
    var created = backend.createInvoice(50, "lookup");
    var looked = backend.lookupInvoice(created.paymentHash());

    assertThat(looked.status()).isEqualTo(InvoiceStatus.SETTLED);
    assertThat(looked.preimage()).hasSize(32);
    assertThat(looked.preimage()).isEqualTo(created.preimage());
    assertThat(looked.paymentHash()).isEqualTo(created.paymentHash());
  }

  @Test
  @DisplayName("lookupInvoice returns PENDING with null preimage for unknown hash")
  void lookupInvoiceReturnsPendingForUnknownHash() {
    var hash = new byte[32];
    hash[0] = 0x42;

    var result = backend.lookupInvoice(hash);

    assertThat(result.status()).isEqualTo(InvoiceStatus.PENDING);
    assertThat(result.preimage()).isNull();
    assertThat(result.paymentHash()).isEqualTo(hash);
  }

  @Test
  @DisplayName("isHealthy always returns true")
  void isHealthyReturnsTrue() {
    assertThat(backend.isHealthy()).isTrue();
  }
}
