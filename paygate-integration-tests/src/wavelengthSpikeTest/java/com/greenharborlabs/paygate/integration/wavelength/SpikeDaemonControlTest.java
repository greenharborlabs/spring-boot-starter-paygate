package com.greenharborlabs.paygate.integration.wavelength;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("Dedicated daemon identity and restoration using owned Java fixture processes")
class SpikeDaemonControlTest {
  @TempDir Path temp;
  private Process initial;
  private Path pidFile;

  @AfterEach
  void stopOnlyFixtureProcesses() throws Exception {
    if (initial != null) initial.destroyForcibly();
    if (pidFile != null && Files.exists(pidFile)) {
      ProcessHandle.of(Long.parseLong(Files.readString(pidFile).strip()))
          .ifPresent(ProcessHandle::destroyForcibly);
    }
  }

  @Test
  void restartsOnlyIdentifiedFixtureAndPreservesWalletDirectoryAndData() throws Exception {
    var environment = fixture(false);
    var wallet = Files.writeString(temp.resolve("wallet-data"), "preserved");
    long original = initial.pid();
    try (var controller = new SpikeDaemonControl(environment)) {
      controller.restart();
      assertThat(Long.parseLong(Files.readString(pidFile).strip())).isNotEqualTo(original);
      controller.faultOn();
      assertThat(Files.exists(temp.resolve("fault"))).isTrue();
      controller.faultOff();
    }
    assertThat(Files.exists(temp.resolve("fault"))).isFalse();
    assertThat(Files.readString(wallet)).isEqualTo("preserved");
  }

  @Test
  void partialFaultFailureStillRestoresOwnedState() throws Exception {
    var environment = fixture(true);
    try (var controller = new SpikeDaemonControl(environment)) {
      assertThatThrownBy(controller::faultOn)
          .hasMessage("Spike daemon control or restoration failed")
          .hasNoCause();
      assertThat(Files.exists(temp.resolve("fault"))).isTrue();
    }
    assertThat(Files.exists(temp.resolve("fault"))).isFalse();
    assertThat(initial.isAlive()).isTrue();
  }

  @Test
  void rejectsDifferentPidBeforeCallingController() throws Exception {
    var environment = fixture(false);
    // The current test JVM lacks the fixture's explicit data-directory argument.
    Files.writeString(pidFile, Long.toString(ProcessHandle.current().pid()));
    try {
      assertThatThrownBy(() -> new SpikeDaemonControl(environment))
          .hasMessage("Dedicated spike daemon ownership is unverified")
          .hasNoCause();
      assertThat(Files.exists(temp.resolve("verified"))).isFalse();
    } finally {
      Files.writeString(pidFile, Long.toString(initial.pid()));
    }
  }

  @Test
  void rejectsControllerReplacementWithoutExecutingIt() throws Exception {
    var environment = fixture(false);
    try (var controller = new SpikeDaemonControl(environment)) {
      Files.writeString(
          Path.of(environment.get("WAVELENGTH_SPIKE_RESTART_EXECUTABLE")), "#!/bin/sh\nexit 0\n");
      assertThatThrownBy(controller::restart)
          .hasMessage("Dedicated spike daemon ownership is unverified");
      assertThat(initial.isAlive()).isTrue();
    }
  }

  private Map<String, String> fixture(boolean failFault) throws Exception {
    temp = temp.toRealPath();
    var java = Path.of(ProcessHandle.current().info().command().orElseThrow()).toRealPath();
    var classpath = System.getProperty("java.class.path");
    // Gradle test workers use a dedicated context classloader rather than java.class.path.
    var location =
        Path.of(Daemon.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    classpath = location + System.getProperty("path.separator") + classpath;
    initial =
        new ProcessBuilder(
                java.toString(), "-cp", classpath, Daemon.class.getName(), temp.toString())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    pidFile = Files.writeString(temp.resolve("pid"), Long.toString(initial.pid()));
    Files.setPosixFilePermissions(pidFile, PosixFilePermissions.fromString("rw-------"));
    var controller = temp.resolve("controller");
    var launch =
        quote(java.toString())
            + " -cp "
            + quote(classpath)
            + " "
            + quote(Daemon.class.getName())
            + " "
            + quote(temp.toString())
            + " </dev/null >/dev/null 2>&1 &\necho $! > "
            + quote(pidFile.toString())
            + "\n";
    Files.writeString(
        controller,
        "#!/bin/sh\nset -eu\ncase \"$1\" in\n"
            + "verify) touch "
            + quote(temp.resolve("verified").toString())
            + ";;\n"
            + "restart) old=$(cat "
            + quote(pidFile.toString())
            + "); kill \"$old\"; i=0; while kill -0 \"$old\" 2>/dev/null; do i=$((i+1)); test $i -lt 100; sleep 0.05; done\n"
            + launch
            + ";;\n"
            + "fault-on) touch "
            + quote(temp.resolve("fault").toString())
            + (failFault ? "; exit 1" : "")
            + ";;\n"
            + "fault-verify) test -f "
            + quote(temp.resolve("fault").toString())
            + ";;\n"
            + "fault-off|restore) rm -f "
            + quote(temp.resolve("fault").toString())
            + ";;\nesac\n");
    Files.setPosixFilePermissions(controller, PosixFilePermissions.fromString("rwx------"));
    var environment = new HashMap<String, String>();
    environment.put("WAVELENGTH_SPIKE_RECEIVER_DATA_DIR", temp.toString());
    environment.put("WAVELENGTH_SPIKE_DAEMON_EXECUTABLE", java.toString());
    environment.put("WAVELENGTH_SPIKE_DAEMON_PID_FILE", pidFile.toString());
    environment.put("WAVELENGTH_SPIKE_RESTART_EXECUTABLE", controller.toString());
    environment.put("WAVELENGTH_SPIKE_DAEMON_SHA256", WavelengthSpikeRun.sha256(java));
    environment.put("WAVELENGTH_SPIKE_CONTROLLER_SHA256", WavelengthSpikeRun.sha256(controller));
    environment.put("WAVELENGTH_SPIKE_CONTROL_REVIEWED", "confirmed");
    return environment;
  }

  private static String quote(String value) {
    return "'" + value.replace("'", "'\\''") + "'";
  }

  /** A real, isolated fixture process; it never opens a wallet or network socket. */
  public static final class Daemon {
    public static void main(String[] ignored) throws InterruptedException {
      Thread.sleep(60_000);
    }
  }
}
