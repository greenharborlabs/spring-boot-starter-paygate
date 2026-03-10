package com.greenharborlabs.l402.spring;

import com.greenharborlabs.l402.core.lightning.Invoice;
import com.greenharborlabs.l402.core.lightning.InvoiceStatus;
import com.greenharborlabs.l402.core.lightning.LightningBackend;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Dummy {@link LightningBackend} for test/development mode (R-014).
 *
 * <p>Creates invoices with random payment hashes and fake bolt11 strings.
 * {@link #lookupInvoice(byte[])} always returns {@link InvoiceStatus#SETTLED}.
 * {@link #isHealthy()} always returns {@code true}.
 *
 * <p><strong>WARNING:</strong> This backend must never be used in production.
 * Preimage verification is effectively skipped because any 32-byte preimage is accepted.
 */
public final class TestModeLightningBackend implements LightningBackend {

    private static final System.Logger log = System.getLogger(TestModeLightningBackend.class.getName());
    private static final int HASH_BYTES = 32;
    private static final Duration DEFAULT_EXPIRY = Duration.ofHours(1);

    private final SecureRandom random;

    public TestModeLightningBackend() {
        this.random = new SecureRandom();
        log.log(System.Logger.Level.WARNING, "L402 test mode is active — DO NOT use in production");
    }

    @Override
    public Invoice createInvoice(long amountSats, String memo) {
        var paymentHash = new byte[HASH_BYTES];
        random.nextBytes(paymentHash);

        var bolt11 = "lntb" + amountSats + "test" + HexFormat.of().formatHex(paymentHash, 0, 4);

        var now = Instant.now();
        return new Invoice(
                paymentHash,
                bolt11,
                amountSats,
                memo,
                InvoiceStatus.PENDING,
                null,
                now,
                now.plus(DEFAULT_EXPIRY)
        );
    }

    @Override
    public Invoice lookupInvoice(byte[] paymentHash) {
        var preimage = new byte[HASH_BYTES];
        random.nextBytes(preimage);

        var now = Instant.now();
        return new Invoice(
                paymentHash,
                "lntb0test" + HexFormat.of().formatHex(paymentHash, 0, 4),
                1,
                null,
                InvoiceStatus.SETTLED,
                preimage,
                now.minus(DEFAULT_EXPIRY),
                now.plus(DEFAULT_EXPIRY)
        );
    }

    @Override
    public boolean isHealthy() {
        return true;
    }
}
