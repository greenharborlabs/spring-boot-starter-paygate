package com.greenharborlabs.paygate.integration;

import com.greenharborlabs.paygate.core.macaroon.Caveat;
import com.greenharborlabs.paygate.core.macaroon.InMemoryRootKeyStore;
import com.greenharborlabs.paygate.core.macaroon.Macaroon;
import com.greenharborlabs.paygate.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.paygate.core.macaroon.MacaroonMinter;
import com.greenharborlabs.paygate.core.macaroon.MacaroonSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test that mints a V2 macaroon in Java, then shells out to
 * Go's {@code go-macaroon} library to verify byte-level deserialization
 * compatibility.
 *
 * <p>Skips (does not fail) when the Go toolchain is unavailable.
 */
@Tag("integration")
class GoInteropIT {

    private static final Path GO_INTEROP_DIR = resolveGoInteropDir();
    private static final int PROCESS_TIMEOUT_SECONDS = 30;

    private static boolean goAvailable;

    @BeforeAll
    static void checkGoToolchain() {
        goAvailable = isGoInstalled();
        assumeTrue(goAvailable, "Go toolchain not available; skipping Go interop tests");
    }

    @Test
    void javaMintedMacaroonIsDeserializableByGo() throws Exception {
        // -- Mint a macaroon in Java --
        var rootKeyStore = new InMemoryRootKeyStore();
        try (var genResult = rootKeyStore.generateRootKey()) {
            byte[] paymentHash = new byte[32];
            // Use a recognizable pattern for the payment hash
            for (int i = 0; i < paymentHash.length; i++) {
                paymentHash[i] = (byte) i;
            }

            byte[] rootKey = genResult.rootKey().value();
            byte[] tokenId = genResult.tokenId();

            var identifier = new MacaroonIdentifier(0, paymentHash, tokenId);
            var caveats = List.of(
                    new Caveat("service", "test-service"),
                    new Caveat("expires_at", "2099-12-31T23:59:59Z")
            );

            Macaroon macaroon = MacaroonMinter.mint(rootKey, identifier, "https://example.com", caveats);
            byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
            String base64Macaroon = Base64.getEncoder().encodeToString(serialized);

            // -- Run Go verify utility --
            GoOutput output = runGoVerify(base64Macaroon);

            // -- Assertions --
            assertThat(output.exitCode())
                    .as("Go process exit code (stderr: %s, stdout: %s)".formatted(
                            output.stderr().strip(), output.stdout().strip()))
                    .isZero();

            assertThat(output.stdout())
                    .as("Go output starts with OK")
                    .contains("OK");

            assertThat(output.stdout())
                    .as("Identifier is 66 bytes (version:2 + paymentHash:32 + tokenId:32)")
                    .contains("ID (len): 66");

            assertThat(output.stdout())
                    .as("Macaroon version is V2")
                    .contains("Version: 2");

            assertThat(output.stdout())
                    .as("Location is preserved")
                    .contains("Location: https://example.com");

            assertThat(output.stdout())
                    .as("Both caveats are present")
                    .contains("Caveats: 2");

            assertThat(output.stdout())
                    .as("First caveat readable")
                    .contains("service=test-service");

            assertThat(output.stdout())
                    .as("Second caveat readable")
                    .contains("expires_at=2099-12-31T23:59:59Z");

            // Verify the identifier hex contains the expected payment hash prefix
            String expectedPaymentHashHex = HexFormat.of().formatHex(paymentHash);
            assertThat(output.stdout())
                    .as("Identifier hex contains payment hash")
                    .contains("ID (hex): 0000" + expectedPaymentHashHex);
        } finally {
            rootKeyStore.close();
        }
    }

    @Test
    void javaMintedMacaroonWithoutLocationIsDeserializableByGo() throws Exception {
        // Verify null location also works (location is optional in V2 format)
        var rootKeyStore = new InMemoryRootKeyStore();
        try (var genResult = rootKeyStore.generateRootKey()) {
            byte[] paymentHash = new byte[32];
            byte[] rootKey = genResult.rootKey().value();
            byte[] tokenId = genResult.tokenId();

            var identifier = new MacaroonIdentifier(0, paymentHash, tokenId);
            var caveats = List.of(new Caveat("service", "no-location-test"));

            Macaroon macaroon = MacaroonMinter.mint(rootKey, identifier, null, caveats);
            byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
            String base64Macaroon = Base64.getEncoder().encodeToString(serialized);

            GoOutput output = runGoVerify(base64Macaroon);

            assertThat(output.exitCode())
                    .as("Go process exit code (stderr: %s, stdout: %s)".formatted(
                            output.stderr().strip(), output.stdout().strip()))
                    .isZero();

            assertThat(output.stdout())
                    .contains("OK");

            assertThat(output.stdout())
                    .contains("ID (len): 66");

            assertThat(output.stdout())
                    .contains("Caveats: 1");

            assertThat(output.stdout())
                    .contains("service=no-location-test");
        } finally {
            rootKeyStore.close();
        }
    }

    @Test
    void javaMintedMacaroonWithNoCaveatsIsDeserializableByGo() throws Exception {
        var rootKeyStore = new InMemoryRootKeyStore();
        try (var genResult = rootKeyStore.generateRootKey()) {
            byte[] paymentHash = new byte[32];
            byte[] rootKey = genResult.rootKey().value();
            byte[] tokenId = genResult.tokenId();

            var identifier = new MacaroonIdentifier(0, paymentHash, tokenId);

            Macaroon macaroon = MacaroonMinter.mint(rootKey, identifier, "https://example.com", List.of());
            byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
            String base64Macaroon = Base64.getEncoder().encodeToString(serialized);

            GoOutput output = runGoVerify(base64Macaroon);

            assertThat(output.exitCode())
                    .as("Go process exit code (stderr: %s, stdout: %s)".formatted(
                            output.stderr().strip(), output.stdout().strip()))
                    .isZero();

            assertThat(output.stdout())
                    .contains("OK");

            assertThat(output.stdout())
                    .contains("ID (len): 66");

            assertThat(output.stdout())
                    .contains("Caveats: 0");
        } finally {
            rootKeyStore.close();
        }
    }

    // -- Helper types and methods --

    private record GoOutput(int exitCode, String stdout, String stderr) {}

    private static GoOutput runGoVerify(String base64Macaroon) throws IOException, InterruptedException {
        // First, ensure Go dependencies are downloaded
        ensureGoDependencies();

        ProcessBuilder pb = new ProcessBuilder("go", "run", "verify.go")
                .directory(GO_INTEROP_DIR.toFile())
                .redirectErrorStream(false);

        Process process = pb.start();

        // Read stdout/stderr concurrently to avoid pipe buffer deadlock
        CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(
                () -> readStream(process.getInputStream()));
        CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(
                () -> readStream(process.getErrorStream()));

        // Write base64 macaroon to stdin, then close
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(base64Macaroon.getBytes(StandardCharsets.UTF_8));
            stdin.write('\n');
            stdin.flush();
        }

        boolean finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError(
                    "Go verify process timed out after %d seconds".formatted(PROCESS_TIMEOUT_SECONDS));
        }

        String stdout = stdoutFuture.join();
        String stderr = stderrFuture.join();

        if (process.exitValue() != 0) {
            System.err.println("Go verify stderr: " + stderr);
            System.err.println("Go verify stdout: " + stdout);
        }

        return new GoOutput(process.exitValue(), stdout, stderr);
    }

    private static String readStream(InputStream stream) {
        try {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "ERROR reading stream: " + e.getMessage();
        }
    }

    private static void ensureGoDependencies() throws IOException, InterruptedException {
        // Run "go mod download" once to fetch dependencies
        ProcessBuilder pb = new ProcessBuilder("go", "mod", "download")
                .directory(GO_INTEROP_DIR.toFile())
                .redirectErrorStream(true);

        Process process = pb.start();
        boolean finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError("go mod download timed out");
        }

        if (process.exitValue() != 0) {
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            throw new AssertionError("go mod download failed (exit %d): %s".formatted(process.exitValue(), output));
        }
    }

    private static boolean isGoInstalled() {
        try {
            ProcessBuilder pb = new ProcessBuilder("go", "version")
                    .redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException | InterruptedException _) {
            return false;
        }
    }

    private static Path resolveGoInteropDir() {
        // Resolve relative to the project root. The test runs from the module directory,
        // so we look for go-interop as a sibling directory within the module.
        Path moduleDir = Path.of(System.getProperty("user.dir"));
        Path candidate = moduleDir.resolve("go-interop");
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        // Fallback: resolve from the project root (when CWD is the root project)
        candidate = moduleDir.resolve("paygate-integration-tests").resolve("go-interop");
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        // Default to the module-relative path; will fail at runtime with a clear error
        return Path.of("paygate-integration-tests", "go-interop").toAbsolutePath();
    }
}
