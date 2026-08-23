package com.greenharborlabs.paygate.lightning.lnd;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LndCredentialFileValidatorTest {

  @Test
  void strictModeAcceptsRegularOwnerOrGroupReadableFile(@TempDir Path tempDir) throws IOException {
    assumePosix(tempDir);
    Path credential = Files.writeString(tempDir.resolve("admin.macaroon"), "test");
    Files.setPosixFilePermissions(
        credential,
        EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ));

    assertThatCode(() -> LndCredentialFileValidator.validate(credential, "macaroon", true))
        .doesNotThrowAnyException();
  }

  @Test
  void strictModeRejectsSymlinkAndDoesNotExposeItsPath(@TempDir Path tempDir) throws IOException {
    Path target = Files.writeString(tempDir.resolve("admin.macaroon"), "test");
    Path link = Files.createSymbolicLink(tempDir.resolve("credential-link"), target);

    assertThatThrownBy(() -> LndCredentialFileValidator.validate(link, "macaroon", true))
        .isInstanceOf(LndException.class)
        .hasMessage("LND macaroon credential file does not meet strict permission requirements")
        .message()
        .doesNotContain(link.toString());
  }

  @Test
  void strictModeRejectsOtherReadableFile(@TempDir Path tempDir) throws IOException {
    assumePosix(tempDir);
    Path credential = Files.writeString(tempDir.resolve("tls.cert"), "test");
    Files.setPosixFilePermissions(
        credential, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OTHERS_READ));

    assertThatThrownBy(
            () -> LndCredentialFileValidator.validate(credential, "TLS certificate", true))
        .isInstanceOf(LndException.class)
        .hasMessage(
            "LND TLS certificate credential file does not meet strict permission requirements");
  }

  private static void assumePosix(Path path) throws IOException {
    Assumptions.assumeTrue(Files.getFileStore(path).supportsFileAttributeView("posix"));
  }
}
