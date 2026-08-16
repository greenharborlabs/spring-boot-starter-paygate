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
public class MacaroonClientInterceptor implements ClientInterceptor, AutoCloseable {

  private static final Metadata.Key<String> MACAROON_KEY =
      Metadata.Key.of("macaroon", Metadata.ASCII_STRING_MARSHALLER);

  private final byte[] macaroonBytes;
  private String macaroonHex;
  private boolean zeroized;

  public MacaroonClientInterceptor(String macaroonHex) {
    this(parseMacaroonHex(macaroonHex));
  }

  public MacaroonClientInterceptor(byte[] macaroonBytes) {
    this(macaroonBytes, bytes -> HexFormat.of().formatHex(bytes));
  }

  MacaroonClientInterceptor(byte[] macaroonBytes, HexEncoder encoder) {
    if (macaroonBytes == null) {
      throw new IllegalArgumentException("macaroonBytes must not be null");
    }
    if (encoder == null) {
      throw new IllegalArgumentException("encoder must not be null");
    }
    this.macaroonBytes = Arrays.copyOf(macaroonBytes, macaroonBytes.length);
    this.macaroonHex = encoder.encode(this.macaroonBytes);
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
      macaroonHex = null;
      zeroized = true;
    }
  }

  /** Ends the credential lifecycle and clears interceptor-owned credential material. */
  @Override
  public void close() {
    zeroize();
  }

  synchronized boolean isZeroized() {
    return zeroized;
  }

  synchronized boolean hasEncodedMacaroon() {
    return macaroonHex != null;
  }

  synchronized boolean hasClearedMacaroonBytes() {
    for (byte macaroonByte : macaroonBytes) {
      if (macaroonByte != 0) {
        return false;
      }
    }
    return true;
  }

  private synchronized String macaroonHex() {
    if (zeroized || macaroonHex == null) {
      throw new IllegalStateException("LND macaroon interceptor has been disposed");
    }
    return macaroonHex;
  }

  @FunctionalInterface
  interface HexEncoder {
    String encode(byte[] bytes);
  }
}
