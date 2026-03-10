package com.greenharborlabs.l402.spring;

import com.greenharborlabs.l402.core.lightning.InvoiceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TestModeLightningBackend")
class TestModeLightningBackendTest {

    private TestModeLightningBackend backend;

    @BeforeEach
    void setUp() {
        backend = new TestModeLightningBackend();
    }

    @Test
    @DisplayName("createInvoice returns PENDING invoice with random 32-byte hash")
    void createInvoiceReturnsPendingWithRandomHash() {
        var invoice = backend.createInvoice(250, "unit test");

        assertThat(invoice.paymentHash()).hasSize(32);
        assertThat(invoice.bolt11()).startsWith("lntb250test");
        assertThat(invoice.amountSats()).isEqualTo(250);
        assertThat(invoice.memo()).isEqualTo("unit test");
        assertThat(invoice.status()).isEqualTo(InvoiceStatus.PENDING);
        assertThat(invoice.preimage()).isNull();
        assertThat(invoice.createdAt()).isNotNull();
        assertThat(invoice.expiresAt()).isAfter(invoice.createdAt());
    }

    @Test
    @DisplayName("createInvoice generates unique payment hashes on successive calls")
    void createInvoiceGeneratesUniqueHashes() {
        var first = backend.createInvoice(100, "a");
        var second = backend.createInvoice(100, "b");

        assertThat(first.paymentHash()).isNotEqualTo(second.paymentHash());
    }

    @Test
    @DisplayName("lookupInvoice always returns SETTLED with a 32-byte preimage")
    void lookupInvoiceAlwaysReturnsSettled() {
        var created = backend.createInvoice(50, "lookup");
        var looked = backend.lookupInvoice(created.paymentHash());

        assertThat(looked.status()).isEqualTo(InvoiceStatus.SETTLED);
        assertThat(looked.preimage()).hasSize(32);
        assertThat(looked.paymentHash()).isEqualTo(created.paymentHash());
    }

    @Test
    @DisplayName("lookupInvoice works with arbitrary 32-byte hash input")
    void lookupInvoiceAcceptsArbitraryHash() {
        var hash = new byte[32];
        hash[0] = 0x42;

        var result = backend.lookupInvoice(hash);

        assertThat(result.status()).isEqualTo(InvoiceStatus.SETTLED);
        assertThat(result.paymentHash()).isEqualTo(hash);
    }

    @Test
    @DisplayName("isHealthy always returns true")
    void isHealthyReturnsTrue() {
        assertThat(backend.isHealthy()).isTrue();
    }
}
