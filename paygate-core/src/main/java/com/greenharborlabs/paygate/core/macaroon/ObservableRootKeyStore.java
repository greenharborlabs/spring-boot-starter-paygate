package com.greenharborlabs.paygate.core.macaroon;

import com.greenharborlabs.paygate.api.crypto.SensitiveBytes;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

public final class ObservableRootKeyStore implements RootKeyStore {

  private static final System.Logger log = System.getLogger(ObservableRootKeyStore.class.getName());

  private final RootKeyStore delegate;
  private final CopyOnWriteArrayList<RootKeyRevocationListener> listeners =
      new CopyOnWriteArrayList<>();

  public ObservableRootKeyStore(RootKeyStore delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  public void addRevocationListener(RootKeyRevocationListener listener) {
    Objects.requireNonNull(listener, "listener");
    listeners.add(listener);
  }

  @Override
  public GenerationResult generateRootKey() {
    return delegate.generateRootKey();
  }

  @Override
  public SensitiveBytes getRootKey(byte[] keyId) {
    return delegate.getRootKey(keyId);
  }

  @Override
  public void revokeRootKey(byte[] keyId) {
    delegate.revokeRootKey(keyId);
    for (RootKeyRevocationListener listener : listeners) {
      try {
        listener.onRootKeyRevoked(Arrays.copyOf(keyId, keyId.length));
      } catch (RuntimeException e) {
        log.log(System.Logger.Level.WARNING, "Revocation listener threw exception", e);
      }
    }
  }

  @Override
  public void close() {
    delegate.close();
  }
}
