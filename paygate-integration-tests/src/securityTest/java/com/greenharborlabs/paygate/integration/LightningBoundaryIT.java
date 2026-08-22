package com.greenharborlabs.paygate.integration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.greenharborlabs.paygate.api.SecurityBounds;
import com.greenharborlabs.paygate.lightning.lnbits.LnbitsBackend;
import com.greenharborlabs.paygate.lightning.lnbits.LnbitsConfig;
import com.greenharborlabs.paygate.lightning.lnbits.LnbitsException;
import com.greenharborlabs.paygate.lightning.lnd.LndBackend;
import com.greenharborlabs.paygate.lightning.lnd.LndException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import lnrpc.LightningGrpc;
import lnrpc.Lnrpc;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Exercises both Lightning adapters' invalid-provider boundary without exposing marker data. */
@Tag("integration")
class LightningBoundaryIT {

  private static final byte[] PAYMENT_HASH = new byte[32];

  @Test
  void invalidBackendDataUsesFixedSecretFreeFailuresAcrossAdapters() throws Exception {
    String marker = "provider-secret-marker-must-not-leak";
    var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(0), 0);
    try {
      server.createContext(
          "/api/v1/payments/" + HexFormat.of().formatHex(PAYMENT_HASH),
          exchange -> {
            byte[] body =
                ("""
                {"paid":false,"details":{"payment_hash":"%s","bolt11":"lnbc1test","amount":"%s"}}
                """
                        .formatted(HexFormat.of().formatHex(PAYMENT_HASH), marker))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          });
      server.start();
      var backend =
          new LnbitsBackend(
              new LnbitsConfig("http://localhost:" + server.getAddress().getPort(), "key", 5, 5, true),
              JsonMapper.builder().build(),
              HttpClient.newHttpClient());

      assertThatThrownBy(() -> backend.lookupInvoice(PAYMENT_HASH))
          .isInstanceOf(LnbitsException.class)
          .hasMessage("LNbits returned invalid backend data")
          .message()
          .doesNotContain(marker);
    } finally {
      server.stop(0);
    }

    String name = InProcessServerBuilder.generateName();
    var grpcServer =
        InProcessServerBuilder.forName(name)
            .directExecutor()
            .addService(
                new LightningGrpc.LightningImplBase() {
                  @Override
                  public void lookupInvoice(
                      Lnrpc.PaymentHash request, StreamObserver<Lnrpc.Invoice> observer) {
                    observer.onNext(
                        Lnrpc.Invoice.newBuilder()
                            .setRHash(ByteString.copyFrom(PAYMENT_HASH))
                            .setPaymentRequest("lnbc1test")
                            .setValue(SecurityBounds.MAX_PRICE_SATS + 1)
                            .build());
                    observer.onCompleted();
                  }
                })
            .build()
            .start();
    var channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    try {
      assertThatThrownBy(() -> new LndBackend(channel).lookupInvoice(PAYMENT_HASH))
          .isInstanceOf(LndException.class)
          .hasMessage("LND returned invalid backend data");
    } finally {
      channel.shutdownNow();
      grpcServer.shutdownNow();
    }
  }
}
