package com.greenharborlabs.paygate.integration.wavelength;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("Wavelength spike credential file validation")
class SpikeCredentialFileValidatorTest {

  private static final String UNAVAILABLE_MESSAGE = "Wavelength credential file is unavailable";
  private static final String UNSAFE_MESSAGE =
      "Wavelength credential file does not meet strict permission requirements";

  @TempDir Path tempDir;

  @BeforeEach
  void requirePosixFileSystem() throws IOException {
    Assumptions.assumeTrue(
        Files.getFileStore(tempDir).supportsFileAttributeView("posix"),
        "POSIX permissions are required for credential validation");
  }

  @Nested
  @DisplayName("accepted files")
  class AcceptedFiles {

    @ParameterizedTest
    @MethodSource(
        "com.greenharborlabs.paygate.integration.wavelength.SpikeCredentialFileValidatorTest#acceptedPermissions")
    void acceptsReadableRegularFilesWithRestrictivePermissions(Set<PosixFilePermission> permissions)
        throws IOException {
      var credential = createCredential(permissions);

      assertThatCode(() -> SpikeCredentialFileValidator.validate(credential))
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("rejected file types")
  class RejectedFileTypes {

    @Test
    void rejectsMissingFileWithFixedSafeError() {
      var missing = tempDir.resolve("missing-credential");

      assertUnavailable(missing);
    }

    @Test
    void rejectsDirectoryWithFixedSafeError() throws IOException {
      var directory = Files.createDirectory(tempDir.resolve("credential-directory"));

      assertUnsafe(directory);
    }

    @Test
    void rejectsSymlinkEvenWhenTargetIsSafe() throws IOException {
      var target = createCredential(EnumSet.of(PosixFilePermission.OWNER_READ));
      var link = Files.createSymbolicLink(tempDir.resolve("credential-link"), target);

      assertUnsafe(link);
    }
  }

  @Nested
  @DisplayName("rejected access")
  class RejectedAccess {

    @Test
    void rejectsUnreadableFileWithFixedSafeError() throws IOException {
      var credential = createCredential(EnumSet.noneOf(PosixFilePermission.class));
      Assumptions.assumeFalse(
          Files.isReadable(credential), "Filesystem does not enforce unreadable temporary files");

      try {
        assertUnavailable(credential);
      } finally {
        Files.setPosixFilePermissions(
            credential,
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
      }
    }

    @ParameterizedTest
    @MethodSource(
        "com.greenharborlabs.paygate.integration.wavelength.SpikeCredentialFileValidatorTest#forbiddenPermissions")
    void rejectsPermissiveModesWithFixedSafeError(PosixFilePermission forbiddenPermission)
        throws IOException {
      var permissions = EnumSet.of(PosixFilePermission.OWNER_READ, forbiddenPermission);
      var credential = createCredential(permissions);

      assertUnsafe(credential);
    }
  }

  @Test
  void rejectsNullWithFixedSafeError() {
    assertThatThrownBy(() -> SpikeCredentialFileValidator.validate(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(UNAVAILABLE_MESSAGE);
  }

  static Stream<Set<PosixFilePermission>> acceptedPermissions() {
    return Stream.of(
        EnumSet.of(PosixFilePermission.OWNER_READ),
        EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
        EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ));
  }

  static Stream<PosixFilePermission> forbiddenPermissions() {
    return Stream.of(
        PosixFilePermission.GROUP_WRITE,
        PosixFilePermission.GROUP_EXECUTE,
        PosixFilePermission.OTHERS_READ,
        PosixFilePermission.OTHERS_WRITE,
        PosixFilePermission.OTHERS_EXECUTE);
  }

  private Path createCredential(Set<PosixFilePermission> permissions) throws IOException {
    var credential = Files.createTempFile(tempDir, "credential-", ".bin");
    Files.write(credential, new byte[] {1, 2, 3, 4});
    Files.setPosixFilePermissions(credential, permissions);
    return credential;
  }

  private static void assertUnavailable(Path path) {
    assertThatThrownBy(() -> SpikeCredentialFileValidator.validate(path))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(UNAVAILABLE_MESSAGE);
  }

  private static void assertUnsafe(Path path) {
    assertThatThrownBy(() -> SpikeCredentialFileValidator.validate(path))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(UNSAFE_MESSAGE);
  }
}
