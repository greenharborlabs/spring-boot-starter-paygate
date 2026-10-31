package com.greenharborlabs.paygate.core.macaroon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Regression tests for root-key revocation and storage-path handling in {@link
 * FileBasedRootKeyStore} (security review 2026-10-18, SR-23). Revocation must delete a regular key
 * file whatever its mode and must never report success while leaving key material resolvable. No
 * key material is printed; assertions compare values in memory only.
 */
class FileRootKeyStoreRevocationRegressionTest {

  private static final HexFormat HEX = HexFormat.of();

  @TempDir Path tempDir;

  private static boolean posix(Path p) {
    return p.getFileSystem().supportedFileAttributeViews().contains("posix");
  }

  @ParameterizedTest(name = "mode {0}")
  @ValueSource(strings = {"r--------", "rw-r-----", "rw-rw-rw-", "---------"})
  void revocationDeletesRegularKeyFileWhateverItsModeAndKeyDoesNotResurrect(String mode)
      throws Exception {
    assumeThat(posix(tempDir)).isTrue();
    Path dir = tempDir.resolve("keys");
    FileBasedRootKeyStore store = new FileBasedRootKeyStore(dir);
    byte[] tokenId;
    try (RootKeyStore.GenerationResult generated = store.generateRootKey()) {
      tokenId = generated.tokenId();
    }
    Path keyFile = dir.resolve(HEX.formatHex(tokenId));
    store.close();

    // Operator hardening or a backup restore changed the key file mode.
    Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString(mode));

    FileBasedRootKeyStore store2 = new FileBasedRootKeyStore(dir);
    try {
      store2.revokeRootKey(tokenId);
      assertThat(Files.exists(keyFile, LinkOption.NOFOLLOW_LINKS))
          .as("key file is deleted by revocation")
          .isFalse();
    } finally {
      store2.close();
    }

    // A fresh store instance must not serve the revoked key again.
    FileBasedRootKeyStore store3 = new FileBasedRootKeyStore(dir);
    try {
      assertThat(store3.getRootKey(tokenId)).as("revoked key stays revoked").isNull();
    } finally {
      store3.close();
    }
  }

  @Test
  void revocationThrowsAndKeepsSymlinkAtKeyPath() throws Exception {
    assumeThat(posix(tempDir)).isTrue();
    Path dir = tempDir.resolve("keys");
    FileBasedRootKeyStore store = new FileBasedRootKeyStore(dir);
    byte[] tokenId = new byte[32];
    tokenId[0] = 7;
    Path target = Files.writeString(tempDir.resolve("elsewhere"), "00".repeat(32));
    Path link = Files.createSymbolicLink(dir.resolve(HEX.formatHex(tokenId)), target);
    try {
      assertThatThrownBy(() -> store.revokeRootKey(tokenId))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not a regular file");
      assertThat(Files.isSymbolicLink(link)).as("link left for investigation").isTrue();
      assertThat(target).as("link target untouched").exists();
    } finally {
      store.close();
    }
  }

  @Test
  void revocationThrowsForSpecialFileAtKeyPath() throws Exception {
    assumeThat(posix(tempDir)).isTrue();
    Path dir = tempDir.resolve("keys");
    FileBasedRootKeyStore store = new FileBasedRootKeyStore(dir);
    byte[] tokenId = new byte[32];
    tokenId[0] = 9;
    Path special = Files.createDirectory(dir.resolve(HEX.formatHex(tokenId)));
    try {
      assertThatThrownBy(() -> store.revokeRootKey(tokenId))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not a regular file");
      assertThat(special).isDirectory();
    } finally {
      store.close();
    }
  }

  @Test
  void revocationEvictsCachedKeyEvenWhenKeyPathIsReplacedByALink() throws Exception {
    assumeThat(posix(tempDir)).isTrue();
    Path dir = tempDir.resolve("keys");
    FileBasedRootKeyStore store = new FileBasedRootKeyStore(dir);
    byte[] tokenId;
    try (RootKeyStore.GenerationResult generated = store.generateRootKey()) {
      tokenId = generated.tokenId();
    }
    Path keyFile = dir.resolve(HEX.formatHex(tokenId));
    Path moved = Files.move(keyFile, tempDir.resolve("moved"));
    Files.createSymbolicLink(keyFile, moved);
    try {
      assertThatThrownBy(() -> store.revokeRootKey(tokenId))
          .isInstanceOf(IllegalStateException.class);
      assertThat(store.getRootKey(tokenId)).as("cached key no longer served").isNull();
    } finally {
      store.close();
    }
  }

  /** Control check: a symlinked leaf storage directory is refused at construction. */
  @Test
  void symlinkedLeafDirectoryIsRefused() throws Exception {
    assumeThat(posix(tempDir)).isTrue();
    Path real = Files.createDirectory(tempDir.resolve("real"));
    Path link = Files.createSymbolicLink(tempDir.resolve("link"), real);
    assertThatThrownBy(() -> new FileBasedRootKeyStore(link))
        .isInstanceOf(IllegalStateException.class);
  }

  /**
   * Documents the host-layer boundary: a symlink in a parent component is followed, and the store
   * writes keys into the link target. SECURITY.md assigns parent-directory protection to the host.
   */
  @Test
  void symlinkedParentDirectoryIsFollowed() throws Exception {
    assumeThat(posix(tempDir)).isTrue();
    Path elsewhere = Files.createDirectory(tempDir.resolve("elsewhere"));
    Path parentLink = Files.createSymbolicLink(tempDir.resolve("parent"), elsewhere);
    FileBasedRootKeyStore store = new FileBasedRootKeyStore(parentLink.resolve("keys"));
    byte[] tokenId;
    try (RootKeyStore.GenerationResult generated = store.generateRootKey()) {
      tokenId = generated.tokenId();
    } finally {
      store.close();
    }
    assertThat(Files.exists(elsewhere.resolve("keys").resolve(HEX.formatHex(tokenId)))).isTrue();
  }

  /** Control check: a pre-existing permissive storage directory is tightened to 0700. */
  @Test
  void preExistingPermissiveDirectoryIsTightened() throws Exception {
    assumeThat(posix(tempDir)).isTrue();
    Path dir = Files.createDirectory(tempDir.resolve("wide"));
    Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxrwxrwx"));
    new FileBasedRootKeyStore(dir).close();
    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)))
        .isEqualTo("rwx------");
  }

  /** Control check: a planted 0644 key file is not served (fail closed, returns null). */
  @Test
  void plantedWorldReadableKeyFileIsIgnored() throws Exception {
    assumeThat(posix(tempDir)).isTrue();
    Path dir = tempDir.resolve("keys2");
    FileBasedRootKeyStore store = new FileBasedRootKeyStore(dir);
    byte[] tokenId = new byte[32];
    tokenId[0] = 1;
    Path planted = dir.resolve(HEX.formatHex(tokenId));
    Files.writeString(planted, "00".repeat(32));
    Files.setPosixFilePermissions(planted, PosixFilePermissions.fromString("rw-r--r--"));
    try {
      assertThat(store.getRootKey(tokenId)).isNull();
    } finally {
      store.close();
    }
  }
}
