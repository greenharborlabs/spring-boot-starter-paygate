package com.greenharborlabs.paygate.core.macaroon;

import static org.assertj.core.api.Assertions.assertThat;

import com.greenharborlabs.paygate.core.lightning.PaymentPreimage;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises JCA paths (fresh Mac.getInstance() in MacaroonCrypto and fresh
 * MessageDigest.getInstance() in PaymentPreimage) under virtual thread concurrency to verify no
 * state leakage between concurrent computations.
 */
class VirtualThreadConcurrencyTest {

  private static final byte[] FIXED_DATA = "l402-concurrency-test-data".getBytes();

  /** Derives a deterministic 32-byte key from an integer index using SHA-256. */
  private static byte[] deriveKey(int index) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      md.update(intToBytes(index));
      return md.digest();
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("SHA-256 must be available", e);
    }
  }

  /** Converts an int to a 4-byte big-endian array. */
  private static byte[] intToBytes(int value) {
    return ByteBuffer.allocate(4).putInt(value).array();
  }

  /** Computes SHA-256 of the given input using a fresh MessageDigest instance. */
  private static byte[] referenceSha256(byte[] input) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(input);
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("SHA-256 must be available", e);
    }
  }

  @Test
  @DisplayName("HMAC results are correct under 100 virtual thread concurrency")
  void hmacCorrectUnderVirtualThreadConcurrency() throws InterruptedException {
    int threadCount = 100;

    // Pre-compute reference results single-threaded
    byte[][] keys = new byte[threadCount][];
    byte[][] expected = new byte[threadCount][];
    for (int i = 0; i < threadCount; i++) {
      keys[i] = deriveKey(i);
      expected[i] = MacaroonCrypto.hmac(keys[i], FIXED_DATA);
    }

    ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
    CountDownLatch ready = new CountDownLatch(threadCount);
    CountDownLatch go = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threadCount);

    for (int i = 0; i < threadCount; i++) {
      int index = i;
      Thread.ofVirtual()
          .name("hmac-vt-", index)
          .start(
              () -> {
                try {
                  ready.countDown();
                  go.await(5, TimeUnit.SECONDS);

                  byte[] result = MacaroonCrypto.hmac(keys[index], FIXED_DATA);
                  assertThat(result)
                      .as("HMAC result for thread %d", index)
                      .isEqualTo(expected[index]);
                } catch (Throwable t) {
                  errors.add(t);
                } finally {
                  done.countDown();
                }
              });
    }

    ready.await(5, TimeUnit.SECONDS);
    go.countDown();
    assertThat(done.await(30, TimeUnit.SECONDS))
        .as("All threads should complete within timeout")
        .isTrue();
    assertThat(errors).as("No errors from virtual threads").isEmpty();
  }

  @Test
  @DisplayName(
      "SHA-256 via PaymentPreimage.matchesHash is correct under 100 virtual thread concurrency")
  void sha256CorrectUnderVirtualThreadConcurrency() throws InterruptedException {
    int threadCount = 100;

    // Pre-compute deterministic preimage bytes and their SHA-256 hashes
    byte[][] preimageBytes = new byte[threadCount][];
    byte[][] hashes = new byte[threadCount][];
    for (int i = 0; i < threadCount; i++) {
      preimageBytes[i] = deriveKey(i); // 32-byte deterministic value
      hashes[i] = referenceSha256(preimageBytes[i]);
    }

    ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
    CountDownLatch ready = new CountDownLatch(threadCount);
    CountDownLatch go = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threadCount);

    for (int i = 0; i < threadCount; i++) {
      int index = i;
      Thread.ofVirtual()
          .name("sha256-vt-", index)
          .start(
              () -> {
                try {
                  ready.countDown();
                  go.await(5, TimeUnit.SECONDS);

                  PaymentPreimage preimage = new PaymentPreimage(preimageBytes[index]);
                  boolean matches = preimage.matchesHash(hashes[index]);
                  assertThat(matches).as("Preimage %d should match its hash", index).isTrue();
                } catch (Throwable t) {
                  errors.add(t);
                } finally {
                  done.countDown();
                }
              });
    }

    ready.await(5, TimeUnit.SECONDS);
    go.countDown();
    assertThat(done.await(30, TimeUnit.SECONDS))
        .as("All threads should complete within timeout")
        .isTrue();
    assertThat(errors).as("No errors from virtual threads").isEmpty();
  }

  @Test
  @DisplayName("HMAC results are correct under 1000 virtual thread high contention")
  void hmacHighContentionSamePrototype() throws InterruptedException {
    int threadCount = 1000;

    // Pre-compute reference results single-threaded
    byte[][] keys = new byte[threadCount][];
    byte[][] expected = new byte[threadCount][];
    for (int i = 0; i < threadCount; i++) {
      keys[i] = deriveKey(i);
      expected[i] = MacaroonCrypto.hmac(keys[i], FIXED_DATA);
    }

    ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
    CountDownLatch ready = new CountDownLatch(threadCount);
    CountDownLatch go = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threadCount);

    for (int i = 0; i < threadCount; i++) {
      int index = i;
      Thread.ofVirtual()
          .name("hmac-stress-vt-", index)
          .start(
              () -> {
                try {
                  ready.countDown();
                  go.await(5, TimeUnit.SECONDS);

                  byte[] result = MacaroonCrypto.hmac(keys[index], FIXED_DATA);
                  assertThat(result)
                      .as("HMAC result for thread %d", index)
                      .isEqualTo(expected[index]);
                } catch (Throwable t) {
                  errors.add(t);
                } finally {
                  done.countDown();
                }
              });
    }

    ready.await(5, TimeUnit.SECONDS);
    go.countDown();
    assertThat(done.await(30, TimeUnit.SECONDS))
        .as("All threads should complete within timeout")
        .isTrue();
    assertThat(errors).as("No errors from virtual threads").isEmpty();
  }
}
