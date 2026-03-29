package com.greenharborlabs.paygate.lightning.lnd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
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
import java.time.Instant;
import lnrpc.LightningGrpc;
import lnrpc.Lnrpc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LndBackendTest {

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
        .hasMessageContaining("Failed to create invoice via LND")
        .hasMessageContaining("UNAVAILABLE")
        .hasMessageContaining("LND node unreachable")
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
        .hasMessageContaining("Failed to lookup invoice via LND")
        .hasMessageContaining("NOT_FOUND")
        .hasMessageContaining("invoice not found")
        .hasCauseInstanceOf(StatusRuntimeException.class);
  }

  @Test
  void createInvoice_returnsInvoiceOnSuccess() throws Exception {
    byte[] rHash = new byte[32];
    rHash[0] = 1;

    var backend =
        startBackendWith(
            new LightningGrpc.LightningImplBase() {
              @Override
              public void addInvoice(
                  Lnrpc.Invoice request,
                  StreamObserver<Lnrpc.AddInvoiceResponse> responseObserver) {
                responseObserver.onNext(
                    Lnrpc.AddInvoiceResponse.newBuilder()
                        .setRHash(ByteString.copyFrom(rHash))
                        .setPaymentRequest("lnbc100n1test")
                        .build());
                responseObserver.onCompleted();
              }
            });

    Invoice invoice = backend.createInvoice(100, "test memo");

    assertThat(invoice.paymentHash()).isEqualTo(rHash);
    assertThat(invoice.bolt11()).isEqualTo("lnbc100n1test");
    assertThat(invoice.amountSats()).isEqualTo(100);
    assertThat(invoice.memo()).isEqualTo("test memo");
    assertThat(invoice.status()).isEqualTo(InvoiceStatus.PENDING);
  }

  @Test
  void lookupInvoice_returnsInvoiceOnSuccess() throws Exception {
    byte[] rHash = new byte[32];
    rHash[0] = 2;
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
        .hasMessageContaining("INTERNAL")
        .message()
        .doesNotContain("null");
  }

  @Test
  void lndTimeoutException_extendsLightningTimeoutException() {
    assertThat(LightningTimeoutException.class).isAssignableFrom(LndTimeoutException.class);
  }
}
