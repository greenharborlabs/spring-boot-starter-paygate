package com.greenharborlabs.paygate.core.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.greenharborlabs.paygate.core.credential.CredentialStore;
import com.greenharborlabs.paygate.core.credential.InMemoryCredentialStore;
import com.greenharborlabs.paygate.core.macaroon.CapabilitiesCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.Caveat;
import com.greenharborlabs.paygate.core.macaroon.CaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.L402VerificationContext;
import com.greenharborlabs.paygate.core.macaroon.Macaroon;
import com.greenharborlabs.paygate.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.paygate.core.macaroon.MacaroonMinter;
import com.greenharborlabs.paygate.core.macaroon.MacaroonSerializer;
import com.greenharborlabs.paygate.core.macaroon.MethodCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.RootKeyStore;
import com.greenharborlabs.paygate.core.macaroon.RouteCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.ServicesCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.ValidUntilCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.VerificationContextKeys;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests preimage validation through the L402Validator pipeline. Covers T067: valid preimage passes,
 * wrong preimage returns INVALID_PREIMAGE.
 */
@DisplayName("PreimageValidation")
class PreimageValidationTest {

  private static final HexFormat HEX = HexFormat.of();
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String SERVICE_NAME = "test-api";
  private static final String REQUEST_ROUTE = "/paid-resource";
  private static final String REQUEST_METHOD = "GET";

  private byte[] rootKey;
  private byte[] preimageBytes;
  private byte[] paymentHash;
  private byte[] tokenIdBytes;
  private String tokenIdHex;
  private Macaroon macaroon;

  private final Map<String, byte[]> rootKeyMap = new HashMap<>();
  private final RootKeyStore rootKeyStore =
      new RootKeyStore() {
        @Override
        public GenerationResult generateRootKey() {
          byte[] key = new byte[32];
          RANDOM.nextBytes(key);
          byte[] tokenId = new byte[32];
          RANDOM.nextBytes(tokenId);
          return new GenerationResult(
              new com.greenharborlabs.paygate.api.crypto.SensitiveBytes(key.clone()), tokenId);
        }

        @Override
        public com.greenharborlabs.paygate.api.crypto.SensitiveBytes getRootKey(byte[] keyId) {
          byte[] stored = rootKeyMap.get(HEX.formatHex(keyId));
          return stored == null
              ? null
              : new com.greenharborlabs.paygate.api.crypto.SensitiveBytes(stored.clone());
        }

        @Override
        public void revokeRootKey(byte[] keyId) {
          rootKeyMap.remove(HEX.formatHex(keyId));
        }
      };

  private CredentialStore credentialStore;

  @BeforeEach
  void setUp() throws NoSuchAlgorithmException {
    rootKeyMap.clear();
    credentialStore = new InMemoryCredentialStore();

    rootKey = new byte[32];
    RANDOM.nextBytes(rootKey);

    preimageBytes = new byte[32];
    RANDOM.nextBytes(preimageBytes);
    paymentHash = MessageDigest.getInstance("SHA-256").digest(preimageBytes);

    tokenIdBytes = new byte[32];
    RANDOM.nextBytes(tokenIdBytes);
    tokenIdHex = HEX.formatHex(tokenIdBytes);

    MacaroonIdentifier identifier = new MacaroonIdentifier(1, paymentHash, tokenIdBytes);
    macaroon = MacaroonMinter.mint(rootKey, identifier, "https://example.com", boundaryCaveats());
    rootKeyMap.put(tokenIdHex, rootKey);
  }

  private String buildAuthHeader(Macaroon mac, String preimageHex) {
    byte[] serialized = MacaroonSerializer.serializeV2(mac);
    String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
    return "L402 " + macaroonBase64 + ":" + preimageHex;
  }

  private List<Caveat> boundaryCaveats() {
    return List.of(
        new Caveat("services", SERVICE_NAME),
        new Caveat("route", REQUEST_ROUTE),
        new Caveat("method", REQUEST_METHOD),
        new Caveat(SERVICE_NAME + "_capabilities", "~"),
        new Caveat(
            SERVICE_NAME + "_valid_until",
            String.valueOf(Instant.now().plusSeconds(3600).getEpochSecond())));
  }

  private List<CaveatVerifier> boundaryVerifiers() {
    return List.of(
        new ServicesCaveatVerifier(10),
        new RouteCaveatVerifier(10),
        new MethodCaveatVerifier(10),
        new CapabilitiesCaveatVerifier(SERVICE_NAME, 50),
        new ValidUntilCaveatVerifier(SERVICE_NAME));
  }

  private L402VerificationContext boundaryContext() {
    return L402VerificationContext.builder()
        .serviceName(SERVICE_NAME)
        .currentTime(Instant.now())
        .requestMetadata(
            Map.of(
                VerificationContextKeys.REQUEST_ROUTE,
                REQUEST_ROUTE,
                VerificationContextKeys.REQUEST_METHOD,
                REQUEST_METHOD))
        .build();
  }

  @Nested
  @DisplayName("valid preimage")
  class ValidPreimage {

    @Test
    @DisplayName("SHA256(preimage) == paymentHash passes validation")
    void validPreimagePassesValidation() {
      String header = buildAuthHeader(macaroon, HEX.formatHex(preimageBytes));

      L402Validator validator =
          new L402Validator(rootKeyStore, credentialStore, boundaryVerifiers(), SERVICE_NAME);

      L402Validator.ValidationResult result = validator.validate(header, boundaryContext());

      assertThat(result.credential()).isNotNull();
      assertThat(result.credential().tokenId()).isEqualTo(tokenIdHex);
      assertThat(result.credential().preimage().matchesHash(paymentHash)).isTrue();
    }
  }

  @Nested
  @DisplayName("wrong preimage")
  class WrongPreimage {

    @Test
    @DisplayName("different 32-byte preimage returns INVALID_PREIMAGE")
    void wrongPreimageReturnsInvalidPreimage() {
      byte[] wrongPreimage = new byte[32];
      RANDOM.nextBytes(wrongPreimage);
      // Ensure it is actually different from the correct preimage
      wrongPreimage[0] = (byte) (preimageBytes[0] ^ 0xFF);

      String header = buildAuthHeader(macaroon, HEX.formatHex(wrongPreimage));

      L402Validator validator =
          new L402Validator(rootKeyStore, credentialStore, boundaryVerifiers(), SERVICE_NAME);

      assertThatThrownBy(() -> validator.validate(header, boundaryContext()))
          .isInstanceOf(L402Exception.class)
          .satisfies(
              e -> {
                L402Exception ex = (L402Exception) e;
                assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_PREIMAGE);
                assertThat(ex.getTokenId()).isEqualTo(tokenIdHex);
              });
    }
  }

  @Nested
  @DisplayName("cached credential")
  class CachedCredential {

    @Test
    @DisplayName("location-only variants take fresh validation and replace the cache slot")
    void locationOnlyVariantsReplaceCacheWithoutExplicitRevocation() {
      try (var trackingStore = new TrackingCredentialStore()) {
        String originalHeader = buildAuthHeader(macaroon, HEX.formatHex(preimageBytes));
        Macaroon relocated =
            new Macaroon(
                macaroon.identifier(),
                "https://mirror.example.com",
                macaroon.caveats(),
                macaroon.signature());
        String relocatedHeader = buildAuthHeader(relocated, HEX.formatHex(preimageBytes));
        L402Validator validator =
            new L402Validator(rootKeyStore, trackingStore, boundaryVerifiers(), SERVICE_NAME);

        assertThat(validator.validate(originalHeader, boundaryContext()).freshValidation())
            .isTrue();
        assertThat(validator.validate(relocatedHeader, boundaryContext()).freshValidation())
            .isTrue();
        assertThat(trackingStore.storeCount()).isEqualTo(2);
        assertThat(trackingStore.revokeCount()).isZero();
        assertCachedVariant(trackingStore, relocated);

        assertThat(validator.validate(originalHeader, boundaryContext()).freshValidation())
            .isTrue();
        assertThat(trackingStore.storeCount()).isEqualTo(3);
        assertThat(trackingStore.revokeCount()).isZero();
        assertCachedVariant(trackingStore, macaroon);
      }
    }

    @Test
    @DisplayName("tampered macaroon with same token and preimage returns INVALID_MACAROON")
    void tamperedMacaroonOnCachedPathReturnsInvalidMacaroon() {
      String validHeader = buildAuthHeader(macaroon, HEX.formatHex(preimageBytes));
      byte[] differentRootKey = new byte[32];
      RANDOM.nextBytes(differentRootKey);
      Macaroon tamperedMacaroon =
          MacaroonMinter.mint(
              differentRootKey,
              new MacaroonIdentifier(1, paymentHash, tokenIdBytes),
              "https://example.com",
              boundaryCaveats());
      String tamperedHeader = buildAuthHeader(tamperedMacaroon, HEX.formatHex(preimageBytes));

      L402Validator validator =
          new L402Validator(rootKeyStore, credentialStore, boundaryVerifiers(), SERVICE_NAME);

      validator.validate(validHeader, boundaryContext());

      assertThatThrownBy(() -> validator.validate(tamperedHeader, boundaryContext()))
          .isInstanceOf(L402Exception.class)
          .satisfies(
              e -> {
                L402Exception ex = (L402Exception) e;
                assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_MACAROON);
                assertThat(ex.getTokenId()).isEqualTo(tokenIdHex);
              });
    }
  }

  private void assertCachedVariant(CredentialStore store, Macaroon expected) {
    var cached = store.get(tokenIdHex);
    try {
      assertThat(cached).isNotNull();
      assertThat(cached.macaroon()).isEqualTo(expected);
    } finally {
      if (cached != null) {
        cached.destroy();
      }
    }
  }

  private static final class TrackingCredentialStore implements CredentialStore, AutoCloseable {
    private final InMemoryCredentialStore delegate = new InMemoryCredentialStore();
    private final AtomicInteger stores = new AtomicInteger();
    private final AtomicInteger revokes = new AtomicInteger();

    @Override
    public void store(String tokenId, L402Credential credential, long ttlSeconds) {
      stores.incrementAndGet();
      delegate.store(tokenId, credential, ttlSeconds);
    }

    @Override
    public L402Credential get(String tokenId) {
      return delegate.get(tokenId);
    }

    @Override
    public void revoke(String tokenId) {
      revokes.incrementAndGet();
      delegate.revoke(tokenId);
    }

    @Override
    public long activeCount() {
      return delegate.activeCount();
    }

    int storeCount() {
      return stores.get();
    }

    int revokeCount() {
      return revokes.get();
    }

    @Override
    public void close() {
      delegate.close();
    }
  }
}
