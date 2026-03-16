package com.greenharborlabs.l402.lightning.lnd;

import com.google.protobuf.ByteString;
import com.greenharborlabs.l402.core.lightning.Invoice;
import com.greenharborlabs.l402.core.lightning.InvoiceStatus;
import com.greenharborlabs.l402.core.lightning.LightningBackend;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import lnrpc.LightningGrpc;
import lnrpc.Lnrpc;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * {@link LightningBackend} implementation backed by an LND node via gRPC.
 */
public class LndBackend implements LightningBackend, AutoCloseable {

    private final ManagedChannel channel;
    private final LightningGrpc.LightningBlockingStub stub;
    private final int rpcDeadlineSeconds;

    public LndBackend(ManagedChannel channel) {
        this(channel, LndConfig.DEFAULT_RPC_DEADLINE_SECONDS);
    }

    public LndBackend(ManagedChannel channel, LndConfig config) {
        this(channel, config.rpcDeadlineSeconds());
    }

    private LndBackend(ManagedChannel channel, int rpcDeadlineSeconds) {
        this.channel = channel;
        this.stub = LightningGrpc.newBlockingStub(channel);
        this.rpcDeadlineSeconds = rpcDeadlineSeconds;
    }

    @Override
    public void close() {
        channel.shutdown();
    }

    private static final long DEFAULT_EXPIRY_SECONDS = 3600L;

    @Override
    public Invoice createInvoice(long amountSats, String memo) {
        try {
            var request = Lnrpc.Invoice.newBuilder()
                    .setValue(amountSats)
                    .setMemo(memo)
                    .setExpiry(DEFAULT_EXPIRY_SECONDS)
                    .build();

            Lnrpc.AddInvoiceResponse addResponse = stub.withDeadlineAfter(rpcDeadlineSeconds, TimeUnit.SECONDS).addInvoice(request);

            Instant createdAt = Instant.now();
            Instant expiresAt = createdAt.plusSeconds(DEFAULT_EXPIRY_SECONDS);

            return new Invoice(
                    addResponse.getRHash().toByteArray(),
                    addResponse.getPaymentRequest(),
                    amountSats,
                    memo,
                    InvoiceStatus.PENDING,
                    null,
                    createdAt,
                    expiresAt
            );
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED) {
                throw new LndTimeoutException(
                        "LND createInvoice timed out after " + rpcDeadlineSeconds + "s", e);
            }
            throw new LndException("Failed to create invoice via LND: " + e.getStatus(), e);
        }
    }

    @Override
    public Invoice lookupInvoice(byte[] paymentHash) {
        try {
            var request = Lnrpc.PaymentHash.newBuilder()
                    .setRHash(ByteString.copyFrom(paymentHash))
                    .build();

            Lnrpc.Invoice lndInvoice = stub.withDeadlineAfter(rpcDeadlineSeconds, TimeUnit.SECONDS).lookupInvoice(request);
            return mapInvoice(lndInvoice);
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED) {
                throw new LndTimeoutException(
                        "LND lookupInvoice timed out after " + rpcDeadlineSeconds + "s", e);
            }
            throw new LndException("Failed to lookup invoice via LND: " + e.getStatus(), e);
        }
    }

    @Override
    public boolean isHealthy() {
        try {
            Lnrpc.GetInfoResponse info = stub.withDeadlineAfter(rpcDeadlineSeconds, TimeUnit.SECONDS).getInfo(
                    Lnrpc.GetInfoRequest.getDefaultInstance());
            return info.getSyncedToChain();
        } catch (StatusRuntimeException _) {
            return false;
        }
    }

    private static Invoice mapInvoice(Lnrpc.Invoice lndInvoice) {
        Instant createdAt = Instant.ofEpochSecond(lndInvoice.getCreationDate());
        Instant expiresAt = createdAt.plusSeconds(lndInvoice.getExpiry());

        byte[] preimage = null;
        if (!lndInvoice.getRPreimage().isEmpty()) {
            preimage = lndInvoice.getRPreimage().toByteArray();
        }

        return new Invoice(
                lndInvoice.getRHash().toByteArray(),
                lndInvoice.getPaymentRequest(),
                lndInvoice.getValue(),
                lndInvoice.getMemo(),
                mapStatus(lndInvoice.getState()),
                preimage,
                createdAt,
                expiresAt
        );
    }

    private static InvoiceStatus mapStatus(Lnrpc.Invoice.InvoiceState state) {
        return switch (state) {
            case SETTLED -> InvoiceStatus.SETTLED;
            case CANCELED -> InvoiceStatus.CANCELLED;
            case OPEN, ACCEPTED -> InvoiceStatus.PENDING;
            case UNRECOGNIZED -> InvoiceStatus.PENDING;
        };
    }
}
