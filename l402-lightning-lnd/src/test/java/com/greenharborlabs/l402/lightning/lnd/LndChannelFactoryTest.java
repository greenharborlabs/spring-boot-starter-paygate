package com.greenharborlabs.l402.lightning.lnd;

import io.grpc.ManagedChannel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LndChannelFactoryTest {

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

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.shutdownNow();
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
            var config = new LndConfig("localhost", 10009, null, null, false, 60, 20, 5, 4_194_304);
            LndChannelFactory.create(config);
        }).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingTlsCertPathThrowsLndException() {
        var config = new LndConfig(
                "localhost", 10009,
                "/nonexistent/path/tls.cert", null,
                false, 60, 20, 5, 4_194_304);

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
                false, 60, 20, 5, 4_194_304);

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
                true, 60, 20, 5, 4_194_304);

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
                true, 60, 20, 5, 4_194_304);

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
                true, 60, 20, 5, 4_194_304);

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
                false, 60, 20, 5, 4_194_304);

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
                false, 60, 20, 5, 4_194_304);

        assertThatThrownBy(() -> LndChannelFactory.create(config))
                .isInstanceOf(LndException.class)
                .hasMessageContaining("Failed to build LND gRPC channel:")
                .hasCauseInstanceOf(Exception.class);
    }
}
