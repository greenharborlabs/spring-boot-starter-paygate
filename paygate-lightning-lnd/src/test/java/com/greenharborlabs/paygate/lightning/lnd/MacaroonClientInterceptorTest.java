package com.greenharborlabs.paygate.lightning.lnd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MacaroonClientInterceptorTest {

  private static final Metadata.Key<String> MACAROON_KEY =
      Metadata.Key.of("macaroon", Metadata.ASCII_STRING_MARSHALLER);

  private static final MethodDescriptor<byte[], byte[]> DUMMY_METHOD =
      MethodDescriptor.<byte[], byte[]>newBuilder()
          .setType(MethodDescriptor.MethodType.UNARY)
          .setFullMethodName("test/method")
          .setRequestMarshaller(new ByteArrayMarshaller())
          .setResponseMarshaller(new ByteArrayMarshaller())
          .build();

  @Test
  void nullMacaroonHex_throwsIllegalArgumentException() {
    assertThatThrownBy(() -> new MacaroonClientInterceptor((String) null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("macaroonHex must not be null");
  }

  @Test
  void nullMacaroonBytes_throwsIllegalArgumentException() {
    assertThatThrownBy(() -> new MacaroonClientInterceptor((byte[]) null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("macaroonBytes must not be null");
  }

  @Test
  void byteArrayConstructorAttachesLowerCaseMacaroonHeader() throws Exception {
    byte[] macaroonBytes = {
      (byte) 0xAB, (byte) 0xCD, (byte) 0xEF, 0x01, 0x23, 0x45, 0x67, (byte) 0x89
    };
    String expectedHex = HexFormat.of().formatHex(macaroonBytes);
    var interceptor = new MacaroonClientInterceptor(macaroonBytes);

    String capturedMacaroon = captureMacaroonHeader(interceptor);

    assertThat(capturedMacaroon).isEqualTo(expectedHex);
  }

  @Test
  void stringConstructorAttachesLowerCaseMacaroonHeader() throws Exception {
    byte[] macaroonBytes = {
      (byte) 0xAB, (byte) 0xCD, (byte) 0xEF, 0x01, 0x23, 0x45, 0x67, (byte) 0x89
    };
    String expectedHex = HexFormat.of().formatHex(macaroonBytes);
    var interceptor = new MacaroonClientInterceptor(expectedHex.toUpperCase());

    String capturedMacaroon = captureMacaroonHeader(interceptor);

    assertThat(capturedMacaroon).isEqualTo(expectedHex);
  }

  @Test
  void byteArrayConstructorDefensivelyCopiesCallerBytes() throws Exception {
    byte[] macaroonBytes = {0x0A, 0x1B, 0x2C, 0x3D};
    String expectedHex = HexFormat.of().formatHex(macaroonBytes);
    var interceptor = new MacaroonClientInterceptor(macaroonBytes);
    macaroonBytes[0] = 0x55;
    macaroonBytes[1] = 0x66;

    String capturedMacaroon = captureMacaroonHeader(interceptor);

    assertThat(capturedMacaroon).isEqualTo(expectedHex);
  }

  @Test
  void zeroizeClearsInterceptorOwnedBytesAndRejectsLaterStartsIdempotently() throws Exception {
    byte[] macaroonBytes = {0x01, 0x23, 0x45, 0x67};
    String originalHex = HexFormat.of().formatHex(macaroonBytes);
    var interceptor = new MacaroonClientInterceptor(macaroonBytes);

    assertThat(captureMacaroonHeader(interceptor)).isEqualTo(originalHex);

    interceptor.zeroize();
    interceptor.zeroize();

    assertThat(interceptor.isZeroized()).isTrue();
    assertThat(interceptor.hasClearedMacaroonBytes()).isTrue();
    assertThat(interceptor.hasEncodedMacaroon()).isFalse();
    assertThatThrownBy(() -> captureMacaroonHeader(interceptor))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("disposed");
  }

  @Test
  void precomputesOneLowercaseEncodingForTenThousandCalls() {
    var encodings = new AtomicInteger();
    var interceptor =
        new MacaroonClientInterceptor(
            new byte[] {(byte) 0xAB, (byte) 0xCD},
            bytes -> {
              encodings.incrementAndGet();
              return HexFormat.of().formatHex(bytes);
            });

    for (var call = 0; call < 10_000; call++) {
      assertThat(captureMacaroonHeaderWithoutTransport(interceptor)).isEqualTo("abcd");
    }

    assertThat(encodings).hasValue(1);
  }

  @Test
  void interceptorIsPublic() {
    assertThat(MacaroonClientInterceptor.class).isPublic();
  }

  @Test
  void emptyStringIsAllowed() {
    var interceptor = new MacaroonClientInterceptor("");
    assertThat(interceptor).isNotNull();
  }

  private static String captureMacaroonHeader(MacaroonClientInterceptor interceptor)
      throws Exception {

    var capturedMacaroon = new AtomicReference<String>();

    ServerInterceptor serverInterceptor =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            capturedMacaroon.set(headers.get(MACAROON_KEY));
            return next.startCall(call, headers);
          }
        };

    ServerServiceDefinition serviceDef =
        ServerServiceDefinition.builder("test")
            .addMethod(
                DUMMY_METHOD,
                (ServerCallHandler<byte[], byte[]>)
                    (call, headers) -> {
                      call.sendHeaders(new Metadata());
                      call.sendMessage(new byte[0]);
                      call.close(Status.OK, new Metadata());
                      return new ServerCall.Listener<>() {};
                    })
            .build();

    String serverName = InProcessServerBuilder.generateName();

    var server =
        InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(ServerInterceptors.intercept(serviceDef, serverInterceptor))
            .build()
            .start();

    ManagedChannel channel =
        InProcessChannelBuilder.forName(serverName).directExecutor().intercept(interceptor).build();

    try {
      ClientCall<byte[], byte[]> call = channel.newCall(DUMMY_METHOD, CallOptions.DEFAULT);
      call.start(new ClientCall.Listener<>() {}, new Metadata());
      call.sendMessage(new byte[0]);
      call.halfClose();
      call.request(1);

      // Allow in-process transport to propagate
      Thread.sleep(100);
    } finally {
      channel.shutdownNow();
      server.shutdownNow();
    }

    return capturedMacaroon.get();
  }

  private static String captureMacaroonHeaderWithoutTransport(
      MacaroonClientInterceptor interceptor) {
    var capturedMacaroon = new AtomicReference<String>();
    Channel next =
        new Channel() {
          @Override
          public String authority() {
            return "test";
          }

          @Override
          public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(
              MethodDescriptor<ReqT, RespT> method, CallOptions options) {
            return new ClientCall<>() {
              @Override
              public void start(Listener<RespT> listener, Metadata headers) {
                capturedMacaroon.set(headers.get(MACAROON_KEY));
              }

              @Override
              public void request(int count) {}

              @Override
              public void cancel(String message, Throwable cause) {}

              @Override
              public void halfClose() {}

              @Override
              public void sendMessage(ReqT message) {}
            };
          }
        };
    interceptor
        .interceptCall(DUMMY_METHOD, CallOptions.DEFAULT, next)
        .start(new ClientCall.Listener<>() {}, new Metadata());
    return capturedMacaroon.get();
  }

  private static class ByteArrayMarshaller implements MethodDescriptor.Marshaller<byte[]> {
    @Override
    public java.io.InputStream stream(byte[] value) {
      return new java.io.ByteArrayInputStream(value);
    }

    @Override
    public byte[] parse(java.io.InputStream stream) {
      try {
        return stream.readAllBytes();
      } catch (java.io.IOException e) {
        throw new RuntimeException(e);
      }
    }
  }
}
