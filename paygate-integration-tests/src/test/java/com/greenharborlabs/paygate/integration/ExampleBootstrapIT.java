package com.greenharborlabs.paygate.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Integration-harness contract for injected disposable LNbits keys. */
@Tag("integration")
@DisplayName("Example LNbits bootstrap harness")
class ExampleBootstrapIT {

  @Test
  @DisplayName("Compose injects a pre-provisioned key and the entrypoint never auto-provisions")
  void composeInjectsDisposableKeyWithoutEntrypointProvisioning() throws IOException {
    Path workspace = workspaceRoot();
    String compose =
        Files.readString(workspace.resolve("integration-tests/docker-compose-lnbits.yml"));
    String entrypoint =
        Files.readString(workspace.resolve("paygate-example-app/docker-entrypoint.sh"));

    assertThat(compose)
        .contains("PAYGATE_LNBITS_API_KEY: \"${LNBITS_API_KEY:-}\"")
        .contains("PAYGATE_EXAMPLE_LNBITS_AUTO_PROVISION: \"false\"");
    assertThat(entrypoint).contains("exec java -jar /app/app.jar").doesNotContain("curl", "wallet");
  }

  private static Path workspaceRoot() {
    Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    for (var depth = 0;
        depth < 4 && directory != null;
        depth++, directory = directory.getParent()) {
      if (Files.isRegularFile(directory.resolve("settings.gradle.kts"))) {
        return directory;
      }
    }
    throw new IllegalStateException("Could not locate the Gradle workspace root");
  }
}
