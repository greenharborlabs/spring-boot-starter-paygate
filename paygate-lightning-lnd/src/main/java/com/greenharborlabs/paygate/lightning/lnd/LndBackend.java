package com.greenharborlabs.paygate.lightning.lnd;

import com.google.protobuf.ByteString;
import com.greenharborlabs.paygate.core.lightning.Invoice;
import com.greenharborlabs.paygate.core.lightning.InvoiceStatus;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.core.macaroon.MacaroonCrypto;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import lnrpc.LightningGrpc;
import lnrpc.Lnrpc;

/** {@link LightningBackend} implementation backed by an LND node via gRPC. */
public class LndBackend implements LightningBackend, AutoCloseable {

  private static final System.Logger log = System.getLogger(LndBackend.class.getName());

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

  private static final int SHUTDOWN_TIMEOUT_SECONDS = 5;

  @Override
  public void close() {
    channel.shutdown();
    try {
      if (!channel.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        log.log(
            System.Logger.Level.WARNING,
            "Channel did not terminate within {0}s, forcing shutdown",
            SHUTDOWN_TIMEOUT_SECONDS);
        channel.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      channel.shutdownNow();
    }
  }

  private static final long DEFAULT_EXPIRY_SECONDS = 3600L;

  @Override
  public Invoice createInvoice(long amountSats, String memo) {
    if (amountSats <= 0) {
      throw new IllegalArgumentException("amountSats must be > 0, got: " + amountSats);
    }
    try {
      var request =
          Lnrpc.Invoice.newBuilder()
              .setValue(amountSats)
              .setMemo(memo)
              .setExpiry(DEFAULT_EXPIRY_SECONDS)
              .build();

      Lnrpc.AddInvoiceResponse addResponse =
          stub.withDeadlineAfter(rpcDeadlineSeconds, TimeUnit.SECONDS).addInvoice(request);
      byte[] paymentHash = addResponse.getRHash().toByteArray();
      requireHashLength(paymentHash, "create invoice response hash");

      Instant createdAt = Instant.now();
      Instant expiresAt = createdAt.plusSeconds(DEFAULT_EXPIRY_SECONDS);

      var invoice =
          new Invoice(
              paymentHash,
              addResponse.getPaymentRequest(),
              amountSats,
              memo,
              InvoiceStatus.PENDING,
              null,
              createdAt,
              expiresAt);

      log.log(System.Logger.Level.DEBUG, "LND createInvoice succeeded");

      return invoice;
    } catch (StatusRuntimeException e) {
      log.log(
          System.Logger.Level.WARNING,
          "LND createInvoice failed: {0} - {1}",
          e.getStatus().getCode(),
          e.getStatus().getDescription());
      if (e.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED) {
        throw new LndTimeoutException(
            "LND createInvoice timed out after " + rpcDeadlineSeconds + "s", e);
      }
      throw new LndException("Failed to create invoice via LND: " + formatStatus(e.getStatus()), e);
    }
  }

  @Override
  public Invoice lookupInvoice(byte[] paymentHash) {
    if (paymentHash == null) {
      throw new IllegalArgumentException("paymentHash must not be null");
    }
    if (paymentHash.length != 32) {
      throw new IllegalArgumentException(
          "paymentHash must be exactly 32 bytes, got " + paymentHash.length);
    }
    try {
      var request =
          Lnrpc.PaymentHash.newBuilder().setRHash(ByteString.copyFrom(paymentHash)).build();

      Lnrpc.Invoice lndInvoice =
          stub.withDeadlineAfter(rpcDeadlineSeconds, TimeUnit.SECONDS).lookupInvoice(request);
      return mapInvoice(lndInvoice, paymentHash);
    } catch (StatusRuntimeException e) {
      log.log(
          System.Logger.Level.WARNING,
          "LND lookupInvoice failed: {0} - {1}",
          e.getStatus().getCode(),
          e.getStatus().getDescription());
      if (e.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED) {
        throw new LndTimeoutException(
            "LND lookupInvoice timed out after " + rpcDeadlineSeconds + "s", e);
      }
      throw new LndException("Failed to lookup invoice via LND: " + formatStatus(e.getStatus()), e);
    }
  }

  private static String formatStatus(Status status) {
    String description = status.getDescription();
    return description != null
        ? status.getCode() + ": " + description
        : String.valueOf(status.getCode());
  }

  @Override
  public boolean isHealthy() {
    try {
      Lnrpc.GetInfoResponse info =
          stub.withDeadlineAfter(rpcDeadlineSeconds, TimeUnit.SECONDS)
              .getInfo(Lnrpc.GetInfoRequest.getDefaultInstance());
      boolean synced = info.getSyncedToChain();
      log.log(System.Logger.Level.DEBUG, "LND health check: syncedToChain={0}", synced);
      return synced;
    } catch (StatusRuntimeException e) {
      log.log(
          System.Logger.Level.WARNING,
          "LND health check failed: {0} - {1}",
          e.getStatus().getCode(),
          e.getStatus().getDescription());
      return false;
    }
  }

  private static Invoice mapInvoice(Lnrpc.Invoice lndInvoice, byte[] requestedPaymentHash) {
    Instant createdAt = Instant.ofEpochSecond(lndInvoice.getCreationDate());
    Instant expiresAt = createdAt.plusSeconds(lndInvoice.getExpiry());

    byte[] responsePaymentHash = lndInvoice.getRHash().toByteArray();
    requireHashLength(responsePaymentHash, "lookup invoice response hash");
    if (!MacaroonCrypto.constantTimeEquals(requestedPaymentHash, responsePaymentHash)) {
      throw new LndException("LND lookup response hash does not match requested invoice");
    }

    byte[] preimage = null;
    if (!lndInvoice.getRPreimage().isEmpty()) {
      preimage = lndInvoice.getRPreimage().toByteArray();
      requireHashLength(preimage, "lookup invoice response preimage");
    }

    InvoiceStatus status = mapStatus(lndInvoice.getState());
    if (status == InvoiceStatus.SETTLED) {
      if (preimage == null) {
        throw new LndException("LND returned a settled invoice without a preimage");
      }
      if (!MacaroonCrypto.constantTimeEquals(sha256(preimage), responsePaymentHash)) {
        throw new LndException("LND settled invoice preimage does not match its payment hash");
      }
    }

    return new Invoice(
        responsePaymentHash,
        lndInvoice.getPaymentRequest(),
        lndInvoice.getValue(),
        lndInvoice.getMemo(),
        status,
        preimage,
        createdAt,
        expiresAt);
  }

  private static void requireHashLength(byte[] value, String field) {
    if (value.length != 32) {
      throw new LndException("LND returned an invalid " + field + " length");
    }
  }

  private static byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 must be available", e);
    }
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
