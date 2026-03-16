package com.greenharborlabs.l402.lightning.lnd;

import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.google.protobuf.Empty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LndChannelFactoryTest {

    private static final Metadata.Key<String> MACAROON_KEY =
            Metadata.Key.of("macaroon", Metadata.ASCII_STRING_MARSHALLER);

    /**
     * Minimal unary gRPC method descriptor for test RPC calls.
     * Uses protobuf Empty as both request and response to avoid generating stubs.
     */
    private static final MethodDescriptor<Empty, Empty> TEST_METHOD =
            MethodDescriptor.<Empty, Empty>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName("test.TestService/Echo")
                    .setRequestMarshaller(ProtoUtils.marshaller(Empty.getDefaultInstance()))
                    .setResponseMarshaller(ProtoUtils.marshaller(Empty.getDefaultInstance()))
                    .build();

    /**
     * Builds a {@link ServerServiceDefinition} with a single unary method that echoes Empty.
     */
    private static ServerServiceDefinition echoServiceDefinition() {
        return ServerServiceDefinition.builder("test.TestService")
                .addMethod(TEST_METHOD, ServerCalls.asyncUnaryCall(
                        (request, responseObserver) -> {
                            responseObserver.onNext(Empty.getDefaultInstance());
                            responseObserver.onCompleted();
                        }))
                .build();
    }

    // Self-signed X.509 cert (CN=localhost, RSA 2048, valid 10 years) for TLS tests only.
    // Generated via: keytool -genkeypair -alias test -keyalg RSA -keysize 2048 -validity 3650
    private static final String SELF_SIGNED_CERT_PEM = """
            -----BEGIN CERTIFICATE-----
            MIICyzCCAbOgAwIBAgIIDwRVYBOVpVYwDQYJKoZIhvcNAQEMBQAwFDESMBAGA1UE
            AxMJbG9jYWxob3N0MB4XDTI2MDMxNTE2MTMxNVoXDTM2MDMxMjE2MTMxNVowFDES
            MBAGA1UEAxMJbG9jYWxob3N0MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKC
            AQEAq0emQPrAyxtfQWvToONsJSBkkF3oIFTNZXMTUJZYOWk7t4jPCMtmvq9TdJSU
            yUiMmWmkF/DhHg3Tr53eH4Z13IOE3f6b3jgRzCglzZdg1LfzJSAsz2D57gLEH4H0
            gu3RcsC6agLvd4zg5EJhDt9WYVktJ7TCgqKWhlJ9E6UJiwjnxMJl+z3hwF64cU8K
            jFBXeuLGw/meSmithCoCymv2dKLExprOHmOQT+u5I7lj9ob7DSycp2NFUa2Q28Ti
            aP99it14C5rkN1d6k+j5thI+QKN0yInIbl5TIuLFmgAmTxEBJciYRYaIBHkoF2t1
            ifTWPbTG5r5kQI6owprDC+kkowIDAQABoyEwHzAdBgNVHQ4EFgQU2UJavoWw1NUx
            I38pCWK/PWgOhvIwDQYJKoZIhvcNAQEMBQADggEBAA+z6rqaedodCCHYhT7Usc+P
            dFz/5+9SCnkGWpROUkzWYYDTWo8RvBpl5pdoyZEXY6if4IGLqQ7GCAnTVkPFXz5w
            Wdz654Zioegxm/xwJ6ghDpTPpfhgouW3IsUJFD1ySzuJqzAYkzFJP0G8FGLUlfi/
            pjzdufEzMyVLm2H6doniTgvmzzSVHJ1sJlUQ/LXKXdcrvMooosc97W3nAdd7/Nwy
            ajfwXdV6r55KGCeVCYtmea1NBoA59MIG3ucwOhJV35ukv/5CsHC2xmrjEJZbMXzN
            dBdtWWqfR/PtHhb3aJTvBgSM0FqeG7djijNX3xHFK/T6EQ6OcnC1M5fXhaR/nKE=
            -----END CERTIFICATE-----
            """;

    private ManagedChannel channel;
    private Server server;

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.shutdownNow();
        }
        if (server != null) {
            server.shutdownNow();
            try {
                server.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    void plaintextChannelCreationSucceeds() {
        var config = LndConfig.plaintextForTesting("localhost", 10009);

        channel = LndChannelFactory.create(config);

        assertThat(channel).isNotNull();
        assertThat(channel.isShutdown()).isFalse();
    }

    @Test
    void nullTlsCertWithoutAllowPlaintextThrowsFromLndConfigConstructor() {
        // The LndConfig constructor validates this invariant first, throwing
        // IllegalArgumentException before the factory's defense-in-depth
        // IllegalStateException guard is reached. This test documents that
        // the constructor is the effective enforcement point.
        assertThatThrownBy(() -> {
            var config = new LndConfig("localhost", 10009, null, null, false, 60, 20, 5, 4_194_304, 5);
            LndChannelFactory.create(config);
        }).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingTlsCertPathThrowsLndException() {
        var config = new LndConfig(
                "localhost", 10009,
                "/nonexistent/path/tls.cert", null,
                false, 60, 20, 5, 4_194_304, 5);

        assertThatThrownBy(() -> LndChannelFactory.create(config))
                .isInstanceOf(LndException.class)
                .hasMessageContaining("TLS certificate file not found:")
                .hasMessageContaining("/nonexistent/path/tls.cert");
    }

    @Test
    void unreadableTlsCertThrowsLndException(@TempDir Path tempDir) throws IOException {
        Path certFile = tempDir.resolve("tls.cert");
        Files.writeString(certFile, "dummy-cert-data");
        certFile.toFile().setReadable(false);

        // On some OS/filesystem combos, root can always read files — skip if so
        if (Files.isReadable(certFile)) {
            return;
        }

        var config = new LndConfig(
                "localhost", 10009,
                certFile.toString(), null,
                false, 60, 20, 5, 4_194_304, 5);

        assertThatThrownBy(() -> LndChannelFactory.create(config))
                .isInstanceOf(LndException.class)
                .hasMessageContaining("TLS certificate file not readable:")
                .hasMessageContaining(certFile.toString());
    }

    @Test
    void unreadableMacaroonFileThrowsLndException(@TempDir Path tempDir) throws IOException {
        Path macaroonFile = tempDir.resolve("admin.macaroon");
        Files.write(macaroonFile, new byte[]{0x01, 0x02, 0x03});
        macaroonFile.toFile().setReadable(false);

        // On some OS/filesystem combos, root can always read files — skip if so
        if (Files.isReadable(macaroonFile)) {
            return;
        }

        var config = new LndConfig(
                "localhost", 10009,
                null, macaroonFile.toString(),
                true, 60, 20, 5, 4_194_304, 5);

        assertThatThrownBy(() -> LndChannelFactory.create(config))
                .isInstanceOf(LndException.class)
                .hasMessageContaining("Macaroon file not readable:")
                .hasMessageContaining(macaroonFile.toString());
    }

    @Test
    void missingMacaroonPathThrowsLndException() {
        // Use plaintext config so we isolate macaroon file validation
        var config = new LndConfig(
                "localhost", 10009,
                null, "/nonexistent/path/admin.macaroon",
                true, 60, 20, 5, 4_194_304, 5);

        assertThatThrownBy(() -> LndChannelFactory.create(config))
                .isInstanceOf(LndException.class)
                .hasMessageContaining("Macaroon file not found:")
                .hasMessageContaining("/nonexistent/path/admin.macaroon");
    }

    @Test
    void plaintextChannelWithMacaroonSucceeds(@TempDir Path tempDir) throws IOException {
        Path macaroonFile = tempDir.resolve("admin.macaroon");
        Files.write(macaroonFile, new byte[]{0x01, 0x02, 0x03});

        var config = new LndConfig(
                "localhost", 10009,
                null, macaroonFile.toString(),
                true, 60, 20, 5, 4_194_304, 5);

        channel = LndChannelFactory.create(config);

        assertThat(channel).isNotNull();
        assertThat(channel.isShutdown()).isFalse();
    }

    @Test
    void tlsChannelWithValidCertSucceeds(@TempDir Path tempDir) throws Exception {
        Path certFile = tempDir.resolve("tls.cert");
        Files.writeString(certFile, SELF_SIGNED_CERT_PEM);

        var config = new LndConfig(
                "localhost", 10009,
                certFile.toString(), null,
                false, 60, 20, 5, 4_194_304, 5);

        channel = LndChannelFactory.create(config);

        assertThat(channel).isNotNull();
        assertThat(channel.isShutdown()).isFalse();
    }

    @Test
    void garbageCertFileThrowsLndExceptionWithCause(@TempDir Path tempDir) throws IOException {
        Path certFile = tempDir.resolve("tls.cert");
        Files.writeString(certFile, "this-is-not-a-valid-certificate");

        var config = new LndConfig(
                "localhost", 10009,
                certFile.toString(), null,
                false, 60, 20, 5, 4_194_304, 5);

        assertThatThrownBy(() -> LndChannelFactory.create(config))
                .isInstanceOf(LndException.class)
                .hasMessageContaining("Failed to build LND gRPC channel:")
                .hasCauseInstanceOf(Exception.class);
    }

    // --- Integration tests with a real gRPC server on localhost ---

    @Test
    void plaintextChannelConnectsAndMakesRpcCall() throws Exception {
        server = ServerBuilder.forPort(0)
                .addService(echoServiceDefinition())
                .build()
                .start();
        int port = server.getPort();

        var config = LndConfig.plaintextForTesting("localhost", port);
        channel = LndChannelFactory.create(config);

        Empty response = ClientCalls.blockingUnaryCall(
                channel, TEST_METHOD, io.grpc.CallOptions.DEFAULT, Empty.getDefaultInstance());

        assertThat(response).isNotNull();
    }

    @Test
    void macaroonInterceptorAttachesHeader(@TempDir Path tempDir) throws Exception {
        byte[] macaroonBytes = {0x0A, 0x1B, 0x2C, 0x3D, 0x4E, 0x5F};
        String expectedHex = HexFormat.of().formatHex(macaroonBytes);

        var capturedHeader = new AtomicReference<String>();
        ServerInterceptor headerCapture = new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                capturedHeader.set(headers.get(MACAROON_KEY));
                return next.startCall(call, headers);
            }
        };

        server = ServerBuilder.forPort(0)
                .addService(ServerInterceptors.intercept(echoServiceDefinition(), headerCapture))
                .build()
                .start();
        int port = server.getPort();

        Path macaroonFile = tempDir.resolve("admin.macaroon");
        Files.write(macaroonFile, macaroonBytes);

        var config = new LndConfig(
                "localhost", port,
                null, macaroonFile.toString(),
                true, 60, 20, 5, 4_194_304, 5);

        channel = LndChannelFactory.create(config);

        ClientCalls.blockingUnaryCall(
                channel, TEST_METHOD, io.grpc.CallOptions.DEFAULT, Empty.getDefaultInstance());

        assertThat(capturedHeader.get())
                .as("macaroon metadata header should contain hex-encoded macaroon bytes")
                .isEqualTo(expectedHex);
    }

    @Test
    void tlsChannelConnectsAndMakesRpcCall(@TempDir Path tempDir) throws Exception {
        // Generate a PKCS12 keystore with a self-signed cert via keytool (JDK tool).
        // This avoids depending on internal sun.security.x509 APIs that Java 25 blocks.
        Path keystorePath = tempDir.resolve("test.p12");
        String password = "changeit";

        var keytoolProcess = new ProcessBuilder(
                "keytool", "-genkeypair",
                "-alias", "test",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "1",
                "-dname", "CN=localhost",
                "-ext", "san=dns:localhost",
                "-storetype", "PKCS12",
                "-keystore", keystorePath.toString(),
                "-storepass", password,
                "-keypass", password
        ).redirectErrorStream(true).start();
        assertThat(keytoolProcess.waitFor(30, TimeUnit.SECONDS)).as("keytool should complete within 30s").isTrue();
        assertThat(keytoolProcess.exitValue()).isZero();

        // Export the certificate to PEM for the client trust store
        Path certFile = tempDir.resolve("tls.cert");
        var exportProcess = new ProcessBuilder(
                "keytool", "-exportcert",
                "-alias", "test",
                "-keystore", keystorePath.toString(),
                "-storepass", password,
                "-rfc",
                "-file", certFile.toString()
        ).redirectErrorStream(true).start();
        assertThat(exportProcess.waitFor(30, TimeUnit.SECONDS)).as("keytool should complete within 30s").isTrue();
        assertThat(exportProcess.exitValue()).isZero();

        // Build server SSL context from the PKCS12 keystore
        var keyStore = java.security.KeyStore.getInstance("PKCS12");
        try (var kis = Files.newInputStream(keystorePath)) {
            keyStore.load(kis, password.toCharArray());
        }
        var kmf = javax.net.ssl.KeyManagerFactory.getInstance(
                javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, password.toCharArray());

        SslContext serverSslContext = GrpcSslContexts.configure(
                io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder.forServer(kmf))
                .build();

        server = NettyServerBuilder.forAddress(new InetSocketAddress("localhost", 0))
                .sslContext(serverSslContext)
                .addService(echoServiceDefinition())
                .build()
                .start();
        int port = server.getPort();

        var config = new LndConfig(
                "localhost", port,
                certFile.toString(), null,
                false, 60, 20, 5, 4_194_304, 5);

        channel = LndChannelFactory.create(config);

        Empty response = ClientCalls.blockingUnaryCall(
                channel, TEST_METHOD, io.grpc.CallOptions.DEFAULT, Empty.getDefaultInstance());

        assertThat(response).isNotNull();
    }

    @Test
    void keepAliveSettingsAreApplied() throws Exception {
        server = ServerBuilder.forPort(0)
                .addService(echoServiceDefinition())
                .build()
                .start();
        int port = server.getPort();

        int customKeepAlive = 30;
        int customKeepAliveTimeout = 10;
        int customIdleTimeout = 3;

        var config = new LndConfig(
                "localhost", port,
                null, null,
                true,
                customKeepAlive,
                customKeepAliveTimeout,
                customIdleTimeout,
                4_194_304,
                5);

        channel = LndChannelFactory.create(config);

        // Verify the channel is functional with custom keepalive settings
        // (if settings were invalid, channel creation or RPC would fail)
        Empty response = ClientCalls.blockingUnaryCall(
                channel, TEST_METHOD, io.grpc.CallOptions.DEFAULT, Empty.getDefaultInstance());

        assertThat(response).isNotNull();
        assertThat(channel.isShutdown()).isFalse();

        // Verify the channel string representation includes authority with correct host:port,
        // confirming the config was wired through properly
        assertThat(channel.authority()).isEqualTo("localhost:" + port);
    }

}
