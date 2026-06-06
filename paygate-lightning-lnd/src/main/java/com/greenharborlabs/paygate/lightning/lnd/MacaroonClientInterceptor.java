package com.greenharborlabs.paygate.lightning.lnd;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * gRPC {@link ClientInterceptor} that attaches an LND macaroon as metadata on every outgoing call.
 */
public class MacaroonClientInterceptor implements ClientInterceptor {

  private static final Metadata.Key<String> MACAROON_KEY =
      Metadata.Key.of("macaroon", Metadata.ASCII_STRING_MARSHALLER);

  private final byte[] macaroonBytes;
  private boolean zeroized;

  public MacaroonClientInterceptor(String macaroonHex) {
    this(parseMacaroonHex(macaroonHex));
  }

  public MacaroonClientInterceptor(byte[] macaroonBytes) {
    if (macaroonBytes == null) {
      throw new IllegalArgumentException("macaroonBytes must not be null");
    }
    this.macaroonBytes = Arrays.copyOf(macaroonBytes, macaroonBytes.length);
  }

  private static byte[] parseMacaroonHex(String macaroonHex) {
    if (macaroonHex == null) {
      throw new IllegalArgumentException("macaroonHex must not be null");
    }
    return HexFormat.of().parseHex(macaroonHex);
  }

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
      MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
    return new ForwardingClientCall.SimpleForwardingClientCall<>(
        next.newCall(method, callOptions)) {
      @Override
      public void start(Listener<RespT> responseListener, Metadata headers) {
        headers.put(MACAROON_KEY, macaroonHex());
        super.start(responseListener, headers);
      }
    };
  }

  synchronized void zeroize() {
    if (!zeroized) {
      Arrays.fill(macaroonBytes, (byte) 0);
      zeroized = true;
    }
  }

  synchronized boolean isZeroized() {
    return zeroized;
  }

  private synchronized String macaroonHex() {
    return HexFormat.of().formatHex(macaroonBytes);
  }
}
