package com.greenharborlabs.paygate.lightning.lnd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.greenharborlabs.paygate.api.SecurityBounds;
import com.greenharborlabs.paygate.core.lightning.Invoice;
import com.greenharborlabs.paygate.core.lightning.InvoiceStatus;
import com.greenharborlabs.paygate.core.lightning.LightningException;
import com.greenharborlabs.paygate.core.lightning.LightningTimeoutException;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import lnrpc.LightningGrpc;
import lnrpc.Lnrpc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LndBackendTest {

  /** BOLT #11 specification example invoice and the payment hash it commits to. */
  private static final String SPEC_BOLT11 =
      "lnbc1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpl2pkx2ctnv5sxxmmwwd5kgetjypeh2ursdae8g6twvus8g6rfwvs8qun0dfjkxaq9qrsgq357wnc5r2ueh7ck6q93dj32dlqnls087fxdwk8qakdyafkq3yap9us6v52vjjsrvywa6rt52cm9r9zqt8r2t7mlcwspyetp5h2tztugp9lfyql";

  private static final byte[] SPEC_PAYMENT_HASH =
      HexFormat.of().parseHex("0001020304050607080900010203040506070809000102030405060708090102");

  private io.grpc.Server grpcServer;
  private ManagedChannel channel;

  private LndBackend startBackendWith(LightningGrpc.LightningImplBase impl) throws Exception {
    String serverName = InProcessServerBuilder.generateName();
    grpcServer =
        InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(impl)
            .build()
            .start();
    channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
    return new LndBackend(channel);
  }

  private LndBackend startBackendReturning(byte[] rHash, String paymentRequest) throws Exception {
    return startBackendWith(
        new LightningGrpc.LightningImplBase() {
          @Override
          public void addInvoice(
              Lnrpc.Invoice request, StreamObserver<Lnrpc.AddInvoiceResponse> responseObserver) {
            responseObserver.onNext(
                Lnrpc.AddInvoiceResponse.newBuilder()
                    .setRHash(ByteString.copyFrom(rHash))
                    .setPaymentRequest(paymentRequest)
                    .build());
            responseObserver.onCompleted();
          }
        });
  }

  @AfterEach
  void tearDown() {
    if (channel != null) {
      channel.shutdownNow();
    }
    if (grpcServer != null) {
      grpcServer.shutdownNow();
    }
  }

  @Test
  void createInvoice_wrapsStatusRuntimeExceptionInLndException() throws Exception {
    var backend =
        startBackendWith(
            new LightningGrpc.LightningImplBase() {
              @Override
              public void addInvoice(
                  Lnrpc.Invoice request,
                  StreamObserver<Lnrpc.AddInvoiceResponse> responseObserver) {
                responseObserver.onError(
                    Status.UNAVAILABLE
                        .withDescription("LND node unreachable")
                        .asRuntimeException());
              }
            });

    assertThatThrownBy(() -> backend.createInvoice(100, "test"))
        .isInstanceOf(LndException.class)
        .isInstanceOf(LightningException.class)
        .hasMessage("LND request failed")
        .hasCauseInstanceOf(StatusRuntimeException.class);
  }

  @Test
  void lookupInvoice_wrapsStatusRuntimeExceptionInLndException() throws Exception {
    var backend =
        startBackendWith(
            new LightningGrpc.LightningImplBase() {
              @Override
              public void lookupInvoice(
                  Lnrpc.PaymentHash request, StreamObserver<Lnrpc.Invoice> responseObserver) {
                responseObserver.onError(
                    Status.NOT_FOUND.withDescription("invoice not found").asRuntimeException());
              }
            });

    byte[] paymentHash = new byte[32];

    assertThatThrownBy(() -> backend.lookupInvoice(paymentHash))
        .isInstanceOf(LndException.class)
        .isInstanceOf(LightningException.class)
        .hasMessage("LND request failed")
        .hasCauseInstanceOf(StatusRuntimeException.class);
  }

  @Test
  void lookupInvoice_throws_whenPaymentHashIsNull() throws Exception {
    var lookupCalls = new AtomicInteger();
    var backend =
        startBackendWith(
            new LightningGrpc.LightningImplBase() {
              @Override
              public void lookupInvoice(
                  Lnrpc.PaymentHash request, StreamObserver<Lnrpc.Invoice> responseObserver) {
                lookupCalls.incrementAndGet();
                responseObserver.onError(Status.INTERNAL.asRuntimeException());
              }
            });

    assertThatThrownBy(() -> backend.lookupInvoice(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("paymentHash");
    assertThat(lookupCalls).hasValue(0);
  }

  @Test
  void lookupInvoice_throws_whenPaymentHashIsEmpty() throws Exception {
    var lookupCalls = new AtomicInteger();
    var backend =
        startBackendWith(
            new LightningGrpc.LightningImplBase() {
              @Override
              public void lookupInvoice(
                  Lnrpc.PaymentHash request, StreamObserver<Lnrpc.Invoice> responseObserver) {
                lookupCalls.incrementAndGet();
                responseObserver.onError(Status.INTERNAL.asRuntimeException());
              }
            });

    assertThatThrownBy(() -> backend.lookupInvoice(new byte[0]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("32");
    assertThat(lookupCalls).hasValue(0);
  }

  @Test
  void lookupInvoice_throws_whenPaymentHashTooShort() throws Exception {
    var lookupCalls = new AtomicInteger();
    var backend =
        startBackendWith(
            new LightningGrpc.LightningImplBase() {
              @Override
              public void lookupInvoice(
                  Lnrpc.PaymentHash request, StreamObserver<Lnrpc.Invoice> responseObserver) {
                lookupCalls.incrementAndGet();
                responseObserver.onError(Status.INTERNAL.asRuntimeException());
              }
            });

    assertThatThrownBy(() -> backend.lookupInvoice(new byte[31]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("32");
    assertThat(lookupCalls).hasValue(0);
  }

  @Test
  void lookupInvoice_throws_whenPaymentHashTooLong() throws Exception {
    var lookupCalls = new AtomicInteger();
    var backend =
        startBackendWith(
            new LightningGrpc.LightningImplBase() {
              @Override
              public void lookupInvoice(
                  Lnrpc.PaymentHash request, StreamObserver<Lnrpc.Invoice> responseObserver) {
                lookupCalls.incrementAndGet();
                responseObserver.onError(Status.INTERNAL.asRuntimeException());
              }
            });

    assertThatThrownBy(() -> backend.lookupInvoice(new byte[33]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("32");
    assertThat(lookupCalls).hasValue(0);
  }

  @Test
  void createInvoice_throws_whenAmountIsZero() throws Exception {
    var addInvoiceCalls = new AtomicInteger();
    var backend =
        startBackendWith(
            new LightningGrpc.LightningImplBase() {
              @Override
              public void addInvoice(
                  Lnrpc.Invoice request,
                  StreamObserver<Lnrpc.AddInvoiceResponse> responseObserver) {
                addInvoiceCalls.incrementAndGet();
                responseObserver.onError(Status.INTERNAL.asRuntimeException());
              }
            });

    assertThatThrownBy(() -> backend.createInvoice(0, "memo"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("amountSats");
    assertThat(addInvoiceCalls).hasValue(0);
  }

  @Test
  void createInvoice_throws_whenAmountIsNegative() throws Exception {
    var addInvoiceCalls = new AtomicInteger();
    var backend =
        startBackendWith(
            new LightningGrpc.LightningImplBase() {
              @Override
              public void addInvoice(
                  Lnrpc.Invoice request,
                  StreamObserver<Lnrpc.AddInvoiceResponse> responseObserver) {
                addInvoiceCalls.incrementAndGet();
                responseObserver.onError(Status.INTERNAL.asRuntimeException());
              }
            });

    assertThatThrownBy(() -> backend.createInvoice(-1, "memo"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("amountSats");
    assertThat(addInvoiceCalls).hasValue(0);
  }

  @Test
  void createInvoice_rejectsAboveMaximumBeforeRpc() throws Exception {
    var addInvoiceCalls = new AtomicInteger();
    var backend =
        startBackendWith(
            new LightningGrpc.LightningImplBase() {
              @Override
              public void addInvoice(
                  Lnrpc.Invoice request,
                  StreamObserver<Lnrpc.AddInvoiceResponse> responseObserver) {
                addInvoiceCalls.incrementAndGet();
              }
            });

    assertThatThrownBy(() -> backend.createInvoice(SecurityBounds.MAX_PRICE_SATS + 1, "memo"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("supported invoice range");
    assertThat(addInvoiceCalls).hasValue(0);
  }

  @Test
  void lookupInvoice_rejectsInvalidProviderAmountBeforeInvoiceConstruction() throws Exception {
    byte[] paymentHash = new byte[32];
    var backend =
        startBackendWith(
            new LightningGrpc.LightningImplBase() {
              @Override
              public void lookupInvoice(
                  Lnrpc.PaymentHash request, StreamObserver<Lnrpc.Invoice> responseObserver) {
                responseObserver.onNext(
                    Lnrpc.Invoice.newBuilder()
                        .setRHash(ByteString.copyFrom(paymentHash))
                        .setPaymentRequest("lnbc1test")
                        .setValue(SecurityBounds.MAX_PRICE_SATS + 1)
                        .setState(Lnrpc.Invoice.InvoiceState.OPEN)
                        .setCreationDate(Instant.now().getEpochSecond())
                        .setExpiry(3600)
                        .build());
                responseObserver.onCompleted();
              }
            });

    assertThatThrownBy(() -> backend.lookupInvoice(paymentHash))
        .isInstanceOf(LndException.class)
        .hasMessage("LND returned invalid backend data");
  }

  @Test
  void createInvoice_returnsInvoiceOnSuccess() throws Exception {
    byte[] rHash = SPEC_PAYMENT_HASH.clone();

    var backend = startBackendReturning(rHash, SPEC_BOLT11);

    Invoice invoice = backend.createInvoice(100, "test memo");

    assertThat(invoice.paymentHash()).isEqualTo(rHash);
    assertThat(invoice.bolt11()).isEqualTo(SPEC_BOLT11);
    assertThat(invoice.amountSats()).isEqualTo(100);
    assertThat(invoice.memo()).isEqualTo("test memo");
    assertThat(invoice.status()).isEqualTo(InvoiceStatus.PENDING);
  }

  @Test
  void createInvoice_rejectsPaymentRequestCommittingToDifferentHash() throws Exception {
    byte[] otherHash = SPEC_PAYMENT_HASH.clone();
    otherHash[31] ^= 1;

    var backend = startBackendReturning(otherHash, SPEC_BOLT11);

    assertThatThrownBy(() -> backend.createInvoice(100, "memo"))
        .isInstanceOf(LndException.class)
        .isInstanceOf(LightningException.class)
        .isNotInstanceOf(LndTimeoutException.class)
        .hasMessage("LND returned invalid backend data")
        .hasNoCause();
  }

  @Test
  void createInvoice_rejectsPaymentRequestWithCorruptedChecksum() throws Exception {
    String corrupted = SPEC_BOLT11.substring(0, SPEC_BOLT11.length() - 1) + "q";

    var backend = startBackendReturning(SPEC_PAYMENT_HASH.clone(), corrupted);

    assertThatThrownBy(() -> backend.createInvoice(100, "memo"))
        .isInstanceOf(LndException.class)
        .hasMessage("LND returned invalid backend data")
        .hasCauseInstanceOf(IllegalArgumentException.class)
        .message()
        .doesNotContain(corrupted);
  }

  @Test
  void createInvoice_rejectsEmptyPaymentRequest() throws Exception {
    var backend = startBackendReturning(SPEC_PAYMENT_HASH.clone(), "");

    assertThatThrownBy(() -> backend.createInvoice(100, "memo"))
        .isInstanceOf(LndException.class)
        .hasMessage("LND returned invalid backend data");
  }

  @Test
  void createInvoice_rejectsWrongLengthHashBeforeParsingPaymentRequest() throws Exception {
    var backend = startBackendReturning(new byte[31], SPEC_BOLT11);

    assertThatThrownBy(() -> backend.createInvoice(100, "memo"))
        .isInstanceOf(LndException.class)
        .hasMessage("LND returned invalid backend data")
        .hasNoCause();
  }

  @Test
  void lookupInvoice_returnsInvoiceOnSuccess() throws Exception {
    byte[] preimage = new byte[32];
    preimage[0] = 2;
    byte[] rHash = sha256(preimage);
    long creationDate = Instant.now().getEpochSecond();

    var backend =
        startBackendWith(
            new LightningGrpc.LightningImplBase() {
              @Override
              public void lookupInvoice(
                  Lnrpc.PaymentHash request, StreamObserver<Lnrpc.Invoice> responseObserver) {
                responseObserver.onNext(
                    Lnrpc.Invoice.newBuilder()
                        .setRHash(ByteString.copyFrom(rHash))
                        .setPaymentRequest("lnbc200n1test")
                        .setValue(200)
                        .setMemo("lookup memo")
                        .setState(Lnrpc.Invoice.InvoiceState.SETTLED)
                        .setRPreimage(ByteString.copyFrom(preimage))
                        .setCreationDate(creationDate)
                        .setExpiry(3600)
                        .build());
                responseObserver.onCompleted();
              }
            });

    Invoice invoice = backend.lookupInvoice(rHash);

    assertThat(invoice.paymentHash()).isEqualTo(rHash);
    assertThat(invoice.status()).isEqualTo(InvoiceStatus.SETTLED);
    assertThat(invoice.amountSats()).isEqualTo(200);
  }

  @Test
  void lookupInvoice_rejectsSettledInvoiceWithWrongLengthHash() throws Exception {
    byte[] queriedHash = new byte[32];
    byte[] preimage = new byte[32];

    var backend =
        startBackendWith(
            new LightningGrpc.LightningImplBase() {
              @Override
              public void lookupInvoice(
                  Lnrpc.PaymentHash request, StreamObserver<Lnrpc.Invoice> responseObserver) {
                responseObserver.onNext(settledInvoice(new byte[31], preimage));
                responseObserver.onCompleted();
              }
            });

    assertThatThrownBy(() -> backend.lookupInvoice(queriedHash)).isInstanceOf(LndException.class);
  }

  @Test
  void lookupInvoice_rejectsSettledInvoiceWithWrongLengthPreimage() throws Exception {
    byte[] queriedHash = new byte[32];
    byte[] validPreimage = new byte[32];

    var backend =
        startBackendWith(
            new LightningGrpc.LightningImplBase() {
              @Override
              public void lookupInvoice(
                  Lnrpc.PaymentHash request, StreamObserver<Lnrpc.Invoice> responseObserver) {
                responseObserver.onNext(settledInvoice(sha256(validPreimage), new byte[31]));
                responseObserver.onCompleted();
              }
            });

    assertThatThrownBy(() -> backend.lookupInvoice(queriedHash)).isInstanceOf(LndException.class);
  }

  @Test
  void lookupInvoice_rejectsSettledInvoiceWithoutPreimage() throws Exception {
    byte[] preimage = new byte[32];
    byte[] paymentHash = sha256(preimage);

    var backend =
        startBackendWith(
            new LightningGrpc.LightningImplBase() {
              @Override
              public void lookupInvoice(
                  Lnrpc.PaymentHash request, StreamObserver<Lnrpc.Invoice> responseObserver) {
                responseObserver.onNext(settledInvoice(paymentHash, null));
                responseObserver.onCompleted();
              }
            });

    assertThatThrownBy(() -> backend.lookupInvoice(paymentHash)).isInstanceOf(LndException.class);
  }

  @Test
  void lookupInvoice_rejectsInvoiceWhoseHashDiffersFromQuery() throws Exception {
    byte[] queriedHash = new byte[32];
    queriedHash[0] = 1;
    byte[] preimage = new byte[32];
    preimage[0] = 2;
    byte[] returnedHash = sha256(preimage);

    var backend =
        startBackendWith(
            new LightningGrpc.LightningImplBase() {
              @Override
              public void lookupInvoice(
                  Lnrpc.PaymentHash request, StreamObserver<Lnrpc.Invoice> responseObserver) {
                responseObserver.onNext(settledInvoice(returnedHash, preimage));
                responseObserver.onCompleted();
              }
            });

    assertThatThrownBy(() -> backend.lookupInvoice(queriedHash)).isInstanceOf(LndException.class);
  }

  @Test
  void close_shutsDownAndTerminatesChannel() throws Exception {
    var backend = startBackendWith(new LightningGrpc.LightningImplBase() {});

    assertThat(channel.isShutdown()).isFalse();
    assertThat(channel.isTerminated()).isFalse();
    backend.close();
    assertThat(channel.isShutdown()).isTrue();
    assertThat(channel.isTerminated()).isTrue();
  }

  @Test
  void isHealthy_returnsFalseOnGrpcFailure() throws Exception {
    var backend =
        startBackendWith(
            new LightningGrpc.LightningImplBase() {
              @Override
              public void getInfo(
                  Lnrpc.GetInfoRequest request,
                  StreamObserver<Lnrpc.GetInfoResponse> responseObserver) {
                responseObserver.onError(Status.UNAVAILABLE.asRuntimeException());
              }
            });

    assertThat(backend.isHealthy()).isFalse();
  }

  // --- DEADLINE_EXCEEDED tests ---

  @Test
  void createInvoice_throwsLndTimeoutExceptionOnDeadlineExceeded() throws Exception {
    var backend =
        startBackendWith(
            new LightningGrpc.LightningImplBase() {
              @Override
              public void addInvoice(
                  Lnrpc.Invoice request,
                  StreamObserver<Lnrpc.AddInvoiceResponse> responseObserver) {
                responseObserver.onError(
                    Status.DEADLINE_EXCEEDED
                        .withDescription("deadline exceeded")
                        .asRuntimeException());
              }
            });

    assertThatThrownBy(() -> backend.createInvoice(100, "test"))
        .isInstanceOf(LndTimeoutException.class)
        .isInstanceOf(LightningTimeoutException.class)
        .hasMessageContaining("LND createInvoice timed out after 5s")
        .hasCauseInstanceOf(StatusRuntimeException.class);
  }

  @Test
  void lookupInvoice_throwsLndTimeoutExceptionOnDeadlineExceeded() throws Exception {
    var backend =
        startBackendWith(
            new LightningGrpc.LightningImplBase() {
              @Override
              public void lookupInvoice(
                  Lnrpc.PaymentHash request, StreamObserver<Lnrpc.Invoice> responseObserver) {
                responseObserver.onError(
                    Status.DEADLINE_EXCEEDED
                        .withDescription("deadline exceeded")
                        .asRuntimeException());
              }
            });

    byte[] paymentHash = new byte[32];

    assertThatThrownBy(() -> backend.lookupInvoice(paymentHash))
        .isInstanceOf(LndTimeoutException.class)
        .isInstanceOf(LightningTimeoutException.class)
        .hasMessageContaining("LND lookupInvoice timed out after 5s")
        .hasCauseInstanceOf(StatusRuntimeException.class);
  }

  @Test
  void isHealthy_returnsFalseOnDeadlineExceeded() throws Exception {
    var backend =
        startBackendWith(
            new LightningGrpc.LightningImplBase() {
              @Override
              public void getInfo(
                  Lnrpc.GetInfoRequest request,
                  StreamObserver<Lnrpc.GetInfoResponse> responseObserver) {
                responseObserver.onError(Status.DEADLINE_EXCEEDED.asRuntimeException());
              }
            });

    assertThat(backend.isHealthy()).isFalse();
  }

  // --- Config constructor tests ---

  @Test
  void constructorWithConfig_usesConfiguredDeadline() throws Exception {
    var config = LndConfig.plaintextForTesting("localhost", 10009);
    String serverName = InProcessServerBuilder.generateName();
    grpcServer =
        InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(
                new LightningGrpc.LightningImplBase() {
                  @Override
                  public void addInvoice(
                      Lnrpc.Invoice request,
                      StreamObserver<Lnrpc.AddInvoiceResponse> responseObserver) {
                    responseObserver.onError(Status.DEADLINE_EXCEEDED.asRuntimeException());
                  }
                })
            .build()
            .start();
    channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
    var backend = new LndBackend(channel, config);

    assertThatThrownBy(() -> backend.createInvoice(100, "test"))
        .isInstanceOf(LndTimeoutException.class)
        .hasMessageContaining("timed out after 5s");
  }

  @Test
  void constructorWithConfig_customDeadlineReflectedInMessage() throws Exception {
    var config = new LndConfig("localhost", 10009, null, null, true, 60, 20, 5, 4_194_304, 15);
    String serverName = InProcessServerBuilder.generateName();
    grpcServer =
        InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(
                new LightningGrpc.LightningImplBase() {
                  @Override
                  public void addInvoice(
                      Lnrpc.Invoice request,
                      StreamObserver<Lnrpc.AddInvoiceResponse> responseObserver) {
                    responseObserver.onError(Status.DEADLINE_EXCEEDED.asRuntimeException());
                  }
                })
            .build()
            .start();
    channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
    var backend = new LndBackend(channel, config);

    assertThatThrownBy(() -> backend.createInvoice(100, "test"))
        .isInstanceOf(LndTimeoutException.class)
        .hasMessageContaining("timed out after 15s");
  }

  @Test
  void createInvoice_handlesNullGrpcStatusDescription() throws Exception {
    var backend =
        startBackendWith(
            new LightningGrpc.LightningImplBase() {
              @Override
              public void addInvoice(
                  Lnrpc.Invoice request,
                  StreamObserver<Lnrpc.AddInvoiceResponse> responseObserver) {
                responseObserver.onError(Status.INTERNAL.asRuntimeException());
              }
            });

    assertThatThrownBy(() -> backend.createInvoice(100, "test"))
        .isInstanceOf(LndException.class)
        .hasMessage("LND request failed")
        .message()
        .doesNotContain("null");
  }

  @Test
  void lndTimeoutException_extendsLightningTimeoutException() {
    assertThat(LightningTimeoutException.class).isAssignableFrom(LndTimeoutException.class);
  }

  private static Lnrpc.Invoice settledInvoice(byte[] paymentHash, byte[] preimage) {
    var builder =
        Lnrpc.Invoice.newBuilder()
            .setRHash(ByteString.copyFrom(paymentHash))
            .setPaymentRequest("lnbc200n1test")
            .setValue(200)
            .setMemo("lookup memo")
            .setState(Lnrpc.Invoice.InvoiceState.SETTLED)
            .setCreationDate(Instant.now().getEpochSecond())
            .setExpiry(3600);
    if (preimage != null) {
      builder.setRPreimage(ByteString.copyFrom(preimage));
    }
    return builder.build();
  }

  private static byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("SHA-256 must be available", e);
    }
  }
}
