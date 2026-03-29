package com.greenharborlabs.paygate.core.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.greenharborlabs.paygate.core.credential.CredentialStore;
import com.greenharborlabs.paygate.core.credential.InMemoryCredentialStore;
import com.greenharborlabs.paygate.core.macaroon.Macaroon;
import com.greenharborlabs.paygate.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.paygate.core.macaroon.MacaroonMinter;
import com.greenharborlabs.paygate.core.macaroon.MacaroonSerializer;
import com.greenharborlabs.paygate.core.macaroon.RootKeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
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

    MacaroonIdentifier identifier = new MacaroonIdentifier(0, paymentHash, tokenIdBytes);
    macaroon = MacaroonMinter.mint(rootKey, identifier, "https://example.com", List.of());
    rootKeyMap.put(tokenIdHex, rootKey);
  }

  private String buildAuthHeader(Macaroon mac, String preimageHex) {
    byte[] serialized = MacaroonSerializer.serializeV2(mac);
    String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
    return "L402 " + macaroonBase64 + ":" + preimageHex;
  }

  @Nested
  @DisplayName("valid preimage")
  class ValidPreimage {

    @Test
    @DisplayName("SHA256(preimage) == paymentHash passes validation")
    void validPreimagePassesValidation() {
      String header = buildAuthHeader(macaroon, HEX.formatHex(preimageBytes));

      L402Validator validator =
          new L402Validator(rootKeyStore, credentialStore, List.of(), SERVICE_NAME);

      L402Validator.ValidationResult result = validator.validate(header);

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
          new L402Validator(rootKeyStore, credentialStore, List.of(), SERVICE_NAME);

      assertThatThrownBy(() -> validator.validate(header))
          .isInstanceOf(L402Exception.class)
          .satisfies(
              e -> {
                L402Exception ex = (L402Exception) e;
                assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_PREIMAGE);
                assertThat(ex.getTokenId()).isEqualTo(tokenIdHex);
              });
    }
  }
}
