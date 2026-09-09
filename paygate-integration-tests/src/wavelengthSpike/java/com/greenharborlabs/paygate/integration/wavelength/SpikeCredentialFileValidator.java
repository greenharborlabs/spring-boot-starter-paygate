package com.greenharborlabs.paygate.integration.wavelength;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/** Validates the Wavelength spike credential file without exposing its path. */
final class SpikeCredentialFileValidator {

  private static final String UNAVAILABLE_MESSAGE = "Wavelength credential file is unavailable";
  private static final String UNSAFE_MESSAGE =
      "Wavelength credential file does not meet strict permission requirements";

  private SpikeCredentialFileValidator() {}

  static void validate(Path path) {
    if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(path)) {
      throw new IllegalArgumentException(UNAVAILABLE_MESSAGE);
    }

    if (!isSafeCredentialFile(path)) {
      throw new IllegalArgumentException(UNSAFE_MESSAGE);
    }
  }

  private static boolean isSafeCredentialFile(Path path) {
    if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      return false;
    }

    try {
      Set<PosixFilePermission> permissions =
          Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
      return !permissions.contains(PosixFilePermission.OTHERS_READ)
          && !permissions.contains(PosixFilePermission.OTHERS_WRITE)
          && !permissions.contains(PosixFilePermission.OTHERS_EXECUTE)
          && !permissions.contains(PosixFilePermission.GROUP_WRITE)
          && !permissions.contains(PosixFilePermission.GROUP_EXECUTE);
    } catch (UnsupportedOperationException | IOException e) {
      return false;
    }
  }
}
