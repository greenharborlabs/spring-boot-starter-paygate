package com.greenharborlabs.paygate.spring;

import com.greenharborlabs.paygate.api.crypto.SensitiveBytes;
import com.greenharborlabs.paygate.core.credential.CredentialStore;
import com.greenharborlabs.paygate.core.lightning.Invoice;
import com.greenharborlabs.paygate.core.lightning.InvoiceStatus;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.core.macaroon.RootKeyStore;
import com.greenharborlabs.paygate.core.protocol.L402Credential;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

final class PaygateTestSupport {

  private PaygateTestSupport() {}

  static byte[] sha256(byte[] input) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(input);
    } catch (Exception e) {
      throw new AssertionError("SHA-256 not available", e);
    }
  }

  static Invoice createStubInvoice(long priceSats) {
    byte[] paymentHash = new byte[32];
    new SecureRandom().nextBytes(paymentHash);
    Instant now = Instant.now();
    return new Invoice(
        paymentHash,
        "lnbc" + priceSats + "n1p0testinvoice",
        priceSats,
        "Test invoice",
        InvoiceStatus.PENDING,
        null,
        now,
        now.plus(1, ChronoUnit.HOURS));
  }

  static class StubLightningBackend implements LightningBackend {

    private volatile boolean healthy = true;
    private volatile boolean throwOnHealthCheck = false;
    private volatile Invoice nextInvoice;
    private final AtomicInteger isHealthyCallCount = new AtomicInteger(0);

    void setHealthy(boolean healthy) {
      this.healthy = healthy;
    }

    void setThrowOnHealthCheck(boolean throwOnHealthCheck) {
      this.throwOnHealthCheck = throwOnHealthCheck;
    }

    void setNextInvoice(Invoice invoice) {
      this.nextInvoice = invoice;
    }

    int getIsHealthyCallCount() {
      return isHealthyCallCount.get();
    }

    void resetIsHealthyCallCount() {
      isHealthyCallCount.set(0);
    }

    @Override
    public Invoice createInvoice(long amountSats, String memo) {
      if (!healthy) {
        throw new RuntimeException("Lightning backend is not available");
      }
      if (nextInvoice != null) {
        return nextInvoice;
      }
      byte[] paymentHash = new byte[32];
      new SecureRandom().nextBytes(paymentHash);
      Instant now = Instant.now();
      return new Invoice(
          paymentHash,
          "lnbc" + amountSats + "n1pstub",
          amountSats,
          memo,
          InvoiceStatus.PENDING,
          null,
          now,
          now.plus(1, ChronoUnit.HOURS));
    }

    @Override
    public Invoice lookupInvoice(byte[] paymentHash) {
      if (!healthy) {
        throw new RuntimeException("Lightning backend is not available");
      }
      return null;
    }

    @Override
    public boolean isHealthy() {
      isHealthyCallCount.incrementAndGet();
      if (throwOnHealthCheck) {
        throw new RuntimeException("Lightning backend health check failed");
      }
      return healthy;
    }
  }

  static class InMemoryTestRootKeyStore implements RootKeyStore {

    private final byte[] rootKey;

    InMemoryTestRootKeyStore(byte[] rootKey) {
      this.rootKey = rootKey.clone();
    }

    @Override
    public GenerationResult generateRootKey() {
      byte[] tokenId = new byte[32];
      new SecureRandom().nextBytes(tokenId);
      return new GenerationResult(new SensitiveBytes(rootKey.clone()), tokenId);
    }

    @Override
    public SensitiveBytes getRootKey(byte[] keyId) {
      return new SensitiveBytes(rootKey.clone());
    }

    @Override
    public void revokeRootKey(byte[] keyId) {
      // no-op for tests
    }
  }

  static class InMemoryTestCredentialStore implements CredentialStore {

    private final Map<String, L402Credential> store = new ConcurrentHashMap<>();

    @Override
    public void store(String tokenId, L402Credential credential, long ttlSeconds) {
      store.put(tokenId, credential);
    }

    @Override
    public L402Credential get(String tokenId) {
      return store.get(tokenId);
    }

    @Override
    public void revoke(String tokenId) {
      store.remove(tokenId);
    }

    @Override
    public long activeCount() {
      return store.size();
    }
  }
}
