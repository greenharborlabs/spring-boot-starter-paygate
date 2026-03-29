package com.greenharborlabs.paygate.lightning.lnd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.CallOptions;
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
    assertThatThrownBy(() -> new MacaroonClientInterceptor(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("macaroonHex must not be null");
  }

  @Test
  void interceptorAttachesMacaroonHeader() throws Exception {
    String expectedHex = "abcdef0123456789";
    var interceptor = new MacaroonClientInterceptor(expectedHex);

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

      assertThat(capturedMacaroon.get()).isEqualTo(expectedHex);
    } finally {
      channel.shutdownNow();
      server.shutdownNow();
    }
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
