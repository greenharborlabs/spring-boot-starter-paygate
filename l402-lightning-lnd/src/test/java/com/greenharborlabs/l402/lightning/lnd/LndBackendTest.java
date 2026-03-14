package com.greenharborlabs.l402.lightning.lnd;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import com.greenharborlabs.l402.core.lightning.Invoice;
import com.greenharborlabs.l402.core.lightning.InvoiceStatus;

import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;

import lnrpc.LightningGrpc;
import lnrpc.Lnrpc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

class LndBackendTest {

    private Server server;
    private ManagedChannel channel;
    private LndBackend backend;

    // --- Fake LND Lightning service used by every test ---

    private FakeLightningService fakeService;

    @BeforeEach
    void setUp() throws Exception {
        fakeService = new FakeLightningService();

        String serverName = InProcessServerBuilder.generateName();

        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(fakeService)
                .build()
                .start();

        channel = InProcessChannelBuilder.forName(serverName)
                .directExecutor()
                .build();

        backend = new LndBackend(channel);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (channel != null && !channel.isShutdown()) {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
        if (server != null && !server.isShutdown()) {
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    // -------------------------------------------------------------------------
    // createInvoice
    // -------------------------------------------------------------------------

    @Test
    void createInvoice_callsAddInvoice() {
        long amountSats = 1000L;
        String memo = "test-invoice";

        byte[] fakePaymentHash = new byte[32];
        fakePaymentHash[0] = (byte) 0xAB;
        String fakeBolt11 = "lnbc1000n1ptest";

        fakeService.addInvoiceResponse = Lnrpc.AddInvoiceResponse.newBuilder()
                .setRHash(ByteString.copyFrom(fakePaymentHash))
                .setPaymentRequest(fakeBolt11)
                .build();

        java.time.Instant before = java.time.Instant.now();
        Invoice result = backend.createInvoice(amountSats, memo);
        java.time.Instant after = java.time.Instant.now();

        assertThat(result).isNotNull();
        assertThat(result.paymentHash()).isEqualTo(fakePaymentHash);
        assertThat(result.bolt11()).isEqualTo(fakeBolt11);
        assertThat(result.amountSats()).isEqualTo(amountSats);
        assertThat(result.memo()).isEqualTo(memo);
        assertThat(result.status()).isEqualTo(InvoiceStatus.PENDING);
        assertThat(result.preimage()).isNull();
        assertThat(result.createdAt()).isBetween(before, after);
        assertThat(result.expiresAt()).isBetween(before.plusSeconds(3600), after.plusSeconds(3600));

        // Verify the AddInvoice RPC received the correct parameters
        assertThat(fakeService.lastAddInvoiceRequest).isNotNull();
        assertThat(fakeService.lastAddInvoiceRequest.getValue()).isEqualTo(amountSats);
        assertThat(fakeService.lastAddInvoiceRequest.getMemo()).isEqualTo(memo);
        assertThat(fakeService.lastAddInvoiceRequest.getExpiry()).isEqualTo(3600L);

        // Verify no LookupInvoice call was made
        assertThat(fakeService.lastPaymentHashRequest).isNull();
    }

    // -------------------------------------------------------------------------
    // lookupInvoice
    // -------------------------------------------------------------------------

    @Test
    void lookupInvoice_callsLookupInvoice() {
        byte[] paymentHash = new byte[32];
        paymentHash[0] = (byte) 0xCD;
        String bolt11 = "lnbc500n1plookup";

        fakeService.lookupInvoiceResponse = Lnrpc.Invoice.newBuilder()
                .setRHash(ByteString.copyFrom(paymentHash))
                .setPaymentRequest(bolt11)
                .setValue(500L)
                .setMemo("lookup-test")
                .setState(Lnrpc.Invoice.InvoiceState.OPEN)
                .setCreationDate(1700000000L)
                .setExpiry(3600L)
                .build();

        Invoice result = backend.lookupInvoice(paymentHash);

        assertThat(result).isNotNull();
        assertThat(result.paymentHash()).isEqualTo(paymentHash);
        assertThat(result.bolt11()).isEqualTo(bolt11);
        assertThat(result.amountSats()).isEqualTo(500L);
        assertThat(result.status()).isEqualTo(InvoiceStatus.PENDING);

        // Verify the LookupInvoice RPC received the correct payment hash
        assertThat(fakeService.lastPaymentHashRequest).isNotNull();
        assertThat(fakeService.lastPaymentHashRequest.getRHash().toByteArray())
                .isEqualTo(paymentHash);
    }

    @Test
    void lookupInvoice_settledInvoice_returnsSettledStatus() {
        byte[] paymentHash = new byte[32];
        paymentHash[0] = (byte) 0xEF;
        byte[] preimage = new byte[32];
        preimage[0] = (byte) 0x01;

        fakeService.lookupInvoiceResponse = Lnrpc.Invoice.newBuilder()
                .setRHash(ByteString.copyFrom(paymentHash))
                .setPaymentRequest("lnbc200n1psettled")
                .setValue(200L)
                .setMemo("settled-test")
                .setState(Lnrpc.Invoice.InvoiceState.SETTLED)
                .setRPreimage(ByteString.copyFrom(preimage))
                .setCreationDate(1700000000L)
                .setExpiry(3600L)
                .build();

        Invoice result = backend.lookupInvoice(paymentHash);

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(InvoiceStatus.SETTLED);
        assertThat(result.preimage()).isEqualTo(preimage);
    }

    // -------------------------------------------------------------------------
    // isHealthy
    // -------------------------------------------------------------------------

    @Test
    void isHealthy_returnsTrue_whenSynced() {
        fakeService.getInfoResponse = Lnrpc.GetInfoResponse.newBuilder()
                .setSyncedToChain(true)
                .build();

        assertThat(backend.isHealthy()).isTrue();
    }

    @Test
    void isHealthy_returnsFalse_whenNotSynced() {
        fakeService.getInfoResponse = Lnrpc.GetInfoResponse.newBuilder()
                .setSyncedToChain(false)
                .build();

        assertThat(backend.isHealthy()).isFalse();
    }

    @Test
    void isHealthy_returnsFalse_whenRpcFails() {
        fakeService.getInfoError = io.grpc.Status.UNAVAILABLE
                .withDescription("node unreachable")
                .asRuntimeException();

        assertThat(backend.isHealthy()).isFalse();
    }

    // =========================================================================
    // Fake gRPC service implementation
    // =========================================================================

    /**
     * In-process fake of the LND Lightning gRPC service.
     * Each test configures the desired response (or error) before invoking the backend.
     */
    private static class FakeLightningService extends LightningGrpc.LightningImplBase {

        // Captured requests for verification
        volatile Lnrpc.Invoice lastAddInvoiceRequest;
        volatile Lnrpc.PaymentHash lastPaymentHashRequest;

        // Configurable responses
        volatile Lnrpc.AddInvoiceResponse addInvoiceResponse;
        volatile Lnrpc.Invoice lookupInvoiceResponse;
        volatile Lnrpc.GetInfoResponse getInfoResponse;

        // Configurable errors
        volatile StatusRuntimeException getInfoError;

        @Override
        public void addInvoice(Lnrpc.Invoice request,
                               StreamObserver<Lnrpc.AddInvoiceResponse> responseObserver) {
            lastAddInvoiceRequest = request;
            if (addInvoiceResponse != null) {
                responseObserver.onNext(addInvoiceResponse);
                responseObserver.onCompleted();
            } else {
                responseObserver.onError(
                        io.grpc.Status.INTERNAL
                                .withDescription("no addInvoiceResponse configured")
                                .asRuntimeException());
            }
        }

        @Override
        public void lookupInvoice(Lnrpc.PaymentHash request,
                                  StreamObserver<Lnrpc.Invoice> responseObserver) {
            lastPaymentHashRequest = request;
            if (lookupInvoiceResponse != null) {
                responseObserver.onNext(lookupInvoiceResponse);
                responseObserver.onCompleted();
            } else {
                responseObserver.onError(
                        io.grpc.Status.NOT_FOUND
                                .withDescription("no lookupInvoiceResponse configured")
                                .asRuntimeException());
            }
        }

        @Override
        public void getInfo(Lnrpc.GetInfoRequest request,
                            StreamObserver<Lnrpc.GetInfoResponse> responseObserver) {
            if (getInfoError != null) {
                responseObserver.onError(getInfoError);
                return;
            }
            if (getInfoResponse != null) {
                responseObserver.onNext(getInfoResponse);
                responseObserver.onCompleted();
            } else {
                responseObserver.onError(
                        io.grpc.Status.INTERNAL
                                .withDescription("no getInfoResponse configured")
                                .asRuntimeException());
            }
        }
    }
}
