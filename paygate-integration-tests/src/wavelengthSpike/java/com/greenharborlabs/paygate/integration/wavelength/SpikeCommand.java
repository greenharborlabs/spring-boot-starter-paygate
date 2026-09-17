package com.greenharborlabs.paygate.integration.wavelength;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Bounded, secret-safe subprocess boundary. Child diagnostics are never evidence. */
final class SpikeCommand {
  static final String FAILURE = "Wavelength owned command failed";

  private SpikeCommand() {}

  static String run(List<String> command, byte[] input, Duration timeout) {
    if (timeout.isNegative()
        || timeout.isZero()
        || timeout.compareTo(Duration.ofMinutes(6)) > 0
        || input.length > 8192) {
      throw new IllegalArgumentException(FAILURE);
    }
    Process process = null;
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
      var child = process;
      var exchange =
          executor.submit(
              () -> {
                try (var output = child.getOutputStream();
                    var stream = child.getInputStream()) {
                  output.write(input);
                  output.close();
                  var bytes = stream.readNBytes(257);
                  if (bytes.length > 256) {
                    throw new IOException(FAILURE);
                  }
                  if (child.waitFor() != 0) {
                    throw new IOException(FAILURE);
                  }
                  return new String(bytes, StandardCharsets.US_ASCII);
                }
              });
      try {
        return exchange.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      } catch (Exception failure) {
        terminate(child);
        exchange.cancel(true);
        if (failure instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
        throw new IllegalStateException(FAILURE);
      }
    } catch (IOException failure) {
      throw new IllegalStateException(FAILURE);
    } finally {
      if (process != null && process.isAlive()) {
        terminate(process);
      }
    }
  }

  private static void terminate(Process process) {
    // Only descendants of the process we started; never process-name-wide termination.
    var descendants = process.descendants().toList();
    process.destroy();
    try {
      if (!process.waitFor(3, TimeUnit.SECONDS)) {
        process.destroyForcibly();
      }
    } catch (InterruptedException interrupted) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
    } finally {
      descendants.forEach(
          child -> {
            if (child.isAlive()) {
              child.destroyForcibly();
            }
          });
    }
  }
}
