package com.greenharborlabs.paygate.integration.wavelength;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Operator-reviewed controller restricted to a positively identified dedicated daemon. */
final class SpikeDaemonControl implements AutoCloseable {
  private static final String FAILURE = "Dedicated spike daemon ownership is unverified";
  private final Path directory;
  private final Path executable;
  private final Path pidFile;
  private final Path controller;
  private final String executableHash;
  private final String controllerHash;
  private final Object directoryKey;
  private Identity identity;
  private boolean dirty;

  SpikeDaemonControl(Map<String, String> environment) {
    try {
      directory = canonical(environment, "WAVELENGTH_SPIKE_RECEIVER_DATA_DIR");
      executable = canonical(environment, "WAVELENGTH_SPIKE_DAEMON_EXECUTABLE");
      pidFile = canonical(environment, "WAVELENGTH_SPIKE_DAEMON_PID_FILE");
      controller = canonical(environment, "WAVELENGTH_SPIKE_RESTART_EXECUTABLE");
      executableHash = environment.get("WAVELENGTH_SPIKE_DAEMON_SHA256");
      controllerHash = environment.get("WAVELENGTH_SPIKE_CONTROLLER_SHA256");
      SpikeCredentialFileValidator.validate(controller);
      SpikeCredentialFileValidator.validate(pidFile);
      directoryKey = Files.readAttributes(directory, BasicFileAttributes.class).fileKey();
      if (directoryKey == null
          || !Files.isExecutable(controller)
          || !Files.isExecutable(executable)
          || !"confirmed".equals(environment.get("WAVELENGTH_SPIKE_CONTROL_REVIEWED"))) {
        throw new IllegalStateException(FAILURE);
      }
      identity = inspect();
      action("verify");
    } catch (Exception failure) {
      throw new IllegalStateException(FAILURE);
    }
  }

  void restart() {
    verifyCurrent();
    dirty = true; // Set before invocation: even a lost/failed response may have changed state.
    action("restart");
    var next = inspect();
    boolean originalStillRunning =
        ProcessHandle.of(identity.pid())
            .filter(ProcessHandle::isAlive)
            .flatMap(process -> process.info().startInstant())
            .filter(identity.started()::equals)
            .isPresent();
    if (next.equals(identity) || originalStillRunning) {
      throw new IllegalStateException("Spike daemon restart was not observed");
    }
    identity = next;
  }

  void faultOn() {
    verifyCurrent();
    dirty = true;
    action("fault-on");
    action("fault-verify");
    verifyCurrent();
  }

  void faultOff() {
    action("fault-off");
    verifyCurrent();
  }

  @Override
  public void close() {
    if (dirty) {
      // Idempotent recovery is required even if restart/fault-on timed out partway through.
      action("restore");
      identity = inspect();
      action("verify");
      dirty = false;
    }
  }

  private void verifyCurrent() {
    if (!identity.equals(inspect())) {
      throw new IllegalStateException(FAILURE);
    }
  }

  private Identity inspect() {
    try {
      if (!directory.toRealPath().equals(directory)
          || !directoryKey.equals(
              Files.readAttributes(directory, BasicFileAttributes.class).fileKey())
          || !WavelengthSpikeRun.sha256(executable).equals(executableHash)
          || !WavelengthSpikeRun.sha256(controller).equals(controllerHash)
          || Files.isSymbolicLink(pidFile)
          || Files.size(pidFile) > 32) {
        throw new IllegalStateException(FAILURE);
      }
      long pid = Long.parseLong(Files.readString(pidFile).strip());
      var process = ProcessHandle.of(pid).orElseThrow();
      var info = process.info();
      if (!process.isAlive()
          || !Path.of(info.command().orElseThrow()).toRealPath().equals(executable)) {
        throw new IllegalStateException(FAILURE);
      }
      // Require an explicit data-directory argument, not a default wallet directory.
      boolean explicitDirectory =
          java.util.Arrays.stream(info.arguments().orElseThrow())
              .anyMatch(arg -> arg.equals(directory.toString()) || arg.endsWith("=" + directory));
      if (!explicitDirectory) {
        throw new IllegalStateException(FAILURE);
      }
      return new Identity(pid, info.startInstant().orElseThrow());
    } catch (Exception failure) {
      throw new IllegalStateException(FAILURE);
    }
  }

  private void action(String action) {
    try {
      if (!WavelengthSpikeRun.sha256(controller).equals(controllerHash)) {
        throw new IllegalStateException(FAILURE);
      }
      if (!SpikeCommand.run(
              List.of(controller.toString(), action), new byte[0], Duration.ofSeconds(30))
          .isEmpty()) {
        throw new IllegalStateException(FAILURE);
      }
    } catch (Exception failure) {
      throw new IllegalStateException("Spike daemon control or restoration failed");
    }
  }

  private static Path canonical(Map<String, String> environment, String key)
      throws java.io.IOException {
    var path = Path.of(environment.get(key));
    if (Files.isSymbolicLink(path)
        || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        || !path.toAbsolutePath().normalize().equals(path.toRealPath())) {
      throw new IllegalStateException(FAILURE);
    }
    return path.toRealPath();
  }

  private record Identity(long pid, Instant started) {}
}
