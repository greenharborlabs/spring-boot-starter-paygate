package com.greenharborlabs.l402.spring;

import com.greenharborlabs.l402.core.lightning.Invoice;
import com.greenharborlabs.l402.core.lightning.InvoiceStatus;
import com.greenharborlabs.l402.core.lightning.LightningBackend;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dummy {@link LightningBackend} for test/development mode (R-014).
 *
 * <p>Creates invoices with valid preimage/paymentHash pairs (where
 * {@code paymentHash = SHA-256(preimage)}) and fake bolt11 strings.
 * The preimage is included on the returned {@link Invoice} so the caller can
 * expose it in test-mode responses, enabling end-to-end testing with curl.
 * {@link #lookupInvoice(byte[])} always returns {@link InvoiceStatus#SETTLED}
 * with the correct preimage.
 * {@link #isHealthy()} always returns {@code true}.
 *
 * <p><strong>WARNING:</strong> This backend must never be used in production.
 */
public final class TestModeLightningBackend implements LightningBackend {

    private static final System.Logger log = System.getLogger(TestModeLightningBackend.class.getName());
    private static final int HASH_BYTES = 32;
    private static final Duration DEFAULT_EXPIRY = Duration.ofHours(1);
    private static final int MAX_PREIMAGE_ENTRIES = 10_000;

    private final SecureRandom random;
    private final Map<ByteBuffer, byte[]> preimagesByHash = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<ByteBuffer, byte[]> eldest) {
                    return size() > MAX_PREIMAGE_ENTRIES;
                }
            });

    public TestModeLightningBackend() {
        this.random = new SecureRandom();
        log.log(System.Logger.Level.WARNING, "L402 test mode is active — DO NOT use in production");
    }

    @Override
    public Invoice createInvoice(long amountSats, String memo) {
        var preimage = new byte[HASH_BYTES];
        random.nextBytes(preimage);
        var paymentHash = sha256(preimage);

        preimagesByHash.put(ByteBuffer.wrap(paymentHash), preimage.clone());

        var bolt11 = "lntb" + amountSats + "test" + HexFormat.of().formatHex(paymentHash, 0, 4);

        var now = Instant.now();
        return new Invoice(
                paymentHash,
                bolt11,
                amountSats,
                memo,
                InvoiceStatus.PENDING,
                preimage,
                now,
                now.plus(DEFAULT_EXPIRY)
        );
    }

    @Override
    public Invoice lookupInvoice(byte[] paymentHash) {
        var preimage = preimagesByHash.get(ByteBuffer.wrap(paymentHash));

        var now = Instant.now();
        var status = preimage != null ? InvoiceStatus.SETTLED : InvoiceStatus.PENDING;
        return new Invoice(
                paymentHash,
                "lntb0test" + HexFormat.of().formatHex(paymentHash, 0, 4),
                1,
                null,
                status,
                preimage,
                now.minus(DEFAULT_EXPIRY),
                now.plus(DEFAULT_EXPIRY)
        );
    }

    @Override
    public boolean isHealthy() {
        return true;
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }
}
