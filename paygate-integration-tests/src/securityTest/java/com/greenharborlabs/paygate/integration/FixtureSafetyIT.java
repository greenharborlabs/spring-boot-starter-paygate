package com.greenharborlabs.paygate.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Verifies the executable safety controls for all supplied local Docker fixtures. */
@Tag("integration")
@DisplayName("Local fixture safety")
class FixtureSafetyIT {

  @Test
  @DisplayName("static inventory and isolated fixture mutations pass their safety controls")
  void staticInventoryAndMutationsPass() throws Exception {
    assertScriptSucceeds("scripts/validate-low-security-fixtures.sh");
    assertScriptSucceeds("scripts/test-low-security-fixtures.sh");
  }

  @Test
  @DisplayName("LNbits setup secrets are unique, persistent, private, and silent")
  void lnbitsSetupSecretControlsPass() throws Exception {
    assertScriptSucceeds("integration-tests/scripts/test-setup-lnbits-security.sh");
  }

  @Test
  @DisplayName("LND producer and non-root consumer credential controls pass")
  void lndCredentialPermissionControlsPass() throws Exception {
    assertScriptSucceeds("integration-tests/scripts/test-lnd-credential-permissions.sh");
  }

  @Test
  @DisplayName("all supplied Compose files render loopback-only application binds")
  void composeFilesRenderLoopbackOnlyApplicationBinds() throws Exception {
    assertComposeRenders("docker-compose.yml", "8080");
    for (var composeFile :
        List.of(
            "integration-tests/docker-compose-lnd.yml",
            "integration-tests/docker-compose-lnbits.yml",
            "integration-tests/docker-compose-lnbits-lnd.yml",
            "integration-tests/docker-compose-lnd-two-node.yml")) {
      assertComposeRenders(composeFile, "18080");
    }
  }

  private static void assertScriptSucceeds(String script) throws IOException, InterruptedException {
    var process =
        new ProcessBuilder("bash", workspaceRoot().resolve(script).toString())
            .directory(workspaceRoot().toFile())
            .redirectErrorStream(true)
            .start();
    var output = new String(process.getInputStream().readAllBytes());

    assertThat(process.waitFor()).describedAs(output).isZero();
  }

  private static void assertComposeRenders(String composeFile, String expectedPublishedPort)
      throws IOException, InterruptedException {
    var process =
        new ProcessBuilder(
                "docker", "compose", "-f", workspaceRoot().resolve(composeFile).toString(), "config")
            .directory(workspaceRoot().toFile())
            .redirectErrorStream(true)
            .start();
    var output = new String(process.getInputStream().readAllBytes());

    assertThat(process.waitFor()).describedAs(output).isZero();
    assertThat(output)
        .contains(
            "host_ip: 127.0.0.1\n"
                + "        target: 8080\n"
                + "        published: \""
                + expectedPublishedPort
                + "\"");
  }

  private static Path workspaceRoot() {
    Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    for (var depth = 0;
        depth < 4 && directory != null;
        depth++, directory = directory.getParent()) {
      if (directory != null && directory.resolve("settings.gradle.kts").toFile().isFile()) {
        return directory;
      }
    }
    throw new IllegalStateException("Could not locate the Gradle workspace root");
  }
}
