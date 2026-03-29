package com.greenharborlabs.paygate.core.credential;

import com.greenharborlabs.paygate.core.macaroon.RootKeyRevocationListener;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Bridges root key revocation events into credential store evictions. When a root key is revoked,
 * the corresponding credential (identified by the hex-encoded key ID) is removed from the
 * credential store.
 */
public final class CredentialCacheEvictionListener implements RootKeyRevocationListener {

  private static final HexFormat HEX = HexFormat.of();

  private final CredentialStore credentialStore;

  public CredentialCacheEvictionListener(CredentialStore credentialStore) {
    this.credentialStore =
        Objects.requireNonNull(credentialStore, "credentialStore must not be null");
  }

  @Override
  public void onRootKeyRevoked(byte[] keyId) {
    String hexTokenId = HEX.formatHex(keyId);
    credentialStore.revoke(hexTokenId);
  }
}
