package com.greenharborlabs.paygate.integration.wavelength;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("Owned subprocess and lifecycle safety (local fixtures only)")
class SpikeCommandTest {
  @TempDir Path directory;

  @Test
  void keepsInputOffArgumentsAndDiscardsDiagnostics() {
    var output =
        SpikeCommand.run(
            List.of("/bin/sh", "-c", "read value; printf ignored >&2; printf DONE"),
            "sensitive-input\n".getBytes(StandardCharsets.US_ASCII),
            Duration.ofSeconds(2));
    assertThat(output).isEqualTo("DONE");
  }

  @Test
  void rejectsOversizedOutputWithoutEchoingIt() {
    assertThatThrownBy(
            () ->
                SpikeCommand.run(
                    List.of(
                        "/bin/sh",
                        "-c",
                        "i=0; while [ $i -lt 300 ]; do printf X; i=$((i+1)); done"),
                    new byte[0],
                    Duration.ofSeconds(2)))
        .hasMessage(SpikeCommand.FAILURE)
        .hasNoCause();
  }

  @Test
  void boundsStalledChildAndKillsOwnedDescendants() {
    var start = System.nanoTime();
    assertThatThrownBy(
            () ->
                SpikeCommand.run(
                    List.of("/bin/sh", "-c", "sleep 30"), new byte[0], Duration.ofMillis(30)))
        .hasMessage(SpikeCommand.FAILURE)
        .hasNoCause();
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(6));
  }

  @Test
  void nonzeroExitNeverIncludesChildOutput() {
    assertThatThrownBy(
            () ->
                SpikeCommand.run(
                    List.of("/bin/sh", "-c", "printf secret-canary; exit 1"),
                    new byte[0],
                    Duration.ofSeconds(2)))
        .hasMessage(SpikeCommand.FAILURE)
        .hasNoCause();
  }

  @Test
  void unverifiedDaemonNeverRunsControllerOrChangesData() throws Exception {
    var marker = Files.writeString(directory.resolve("wallet"), "preserve");
    assertThatThrownBy(() -> new SpikeDaemonControl(Map.of()))
        .hasMessage("Dedicated spike daemon ownership is unverified")
        .hasNoCause();
    assertThat(Files.readString(marker)).isEqualTo("preserve");
  }
}
