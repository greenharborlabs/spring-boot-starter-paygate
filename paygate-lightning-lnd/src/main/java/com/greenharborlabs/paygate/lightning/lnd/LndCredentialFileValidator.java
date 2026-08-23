package com.greenharborlabs.paygate.lightning.lnd;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/** Assesses LND credential and trust files without exposing their paths in diagnostics. */
final class LndCredentialFileValidator {

  private static final System.Logger log = System.getLogger(LndChannelFactory.class.getName());

  private LndCredentialFileValidator() {}

  /**
   * Validates a credential file before it is opened or used to allocate a channel.
   *
   * <p>Strict mode requires a readable, regular, non-symlink file with POSIX permissions that do
   * not permit other-user access or group write/execute access. Compatibility mode preserves the
   * existing file checks and emits one path-free warning when strict assessment cannot pass.
   */
  static void validate(Path path, String credentialType, boolean strictFilePermissions) {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(path)) {
      throw new LndException("LND " + credentialType + " credential file is unavailable");
    }

    boolean safe = isSafeStrictFile(path);
    if (strictFilePermissions && !safe) {
      throw new LndException(
          "LND "
              + credentialType
              + " credential file does not meet strict permission requirements");
    }
    if (!strictFilePermissions && !safe) {
      log.log(
          System.Logger.Level.WARNING,
          "LND {0} credential file could not be verified for strict permissions",
          credentialType);
    }
  }

  private static boolean isSafeStrictFile(Path path) {
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
