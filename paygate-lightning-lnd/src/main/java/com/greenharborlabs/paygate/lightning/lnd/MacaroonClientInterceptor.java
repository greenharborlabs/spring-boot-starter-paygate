package com.greenharborlabs.paygate.lightning.lnd;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;

/**
 * gRPC {@link ClientInterceptor} that attaches an LND macaroon as metadata
 * on every outgoing call.
 */
public class MacaroonClientInterceptor implements ClientInterceptor {

    private static final Metadata.Key<String> MACAROON_KEY =
            Metadata.Key.of("macaroon", Metadata.ASCII_STRING_MARSHALLER);

    private final String macaroonHex;

    public MacaroonClientInterceptor(String macaroonHex) {
        if (macaroonHex == null) {
            throw new IllegalArgumentException("macaroonHex must not be null");
        }
        this.macaroonHex = macaroonHex;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        return new ForwardingClientCall.SimpleForwardingClientCall<>(
                next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                headers.put(MACAROON_KEY, macaroonHex);
                super.start(responseListener, headers);
            }
        };
    }
}
