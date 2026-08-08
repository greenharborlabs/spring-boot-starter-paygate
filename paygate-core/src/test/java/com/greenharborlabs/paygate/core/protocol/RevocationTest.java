package com.greenharborlabs.paygate.core.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.greenharborlabs.paygate.api.crypto.SensitiveBytes;
import com.greenharborlabs.paygate.core.credential.CredentialStore;
import com.greenharborlabs.paygate.core.credential.InMemoryCredentialStore;
import com.greenharborlabs.paygate.core.macaroon.CapabilitiesCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.Caveat;
import com.greenharborlabs.paygate.core.macaroon.CaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.InMemoryRootKeyStore;
import com.greenharborlabs.paygate.core.macaroon.L402VerificationContext;
import com.greenharborlabs.paygate.core.macaroon.Macaroon;
import com.greenharborlabs.paygate.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.paygate.core.macaroon.MacaroonMinter;
import com.greenharborlabs.paygate.core.macaroon.MacaroonSerializer;
import com.greenharborlabs.paygate.core.macaroon.MethodCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.RootKeyStore;
import com.greenharborlabs.paygate.core.macaroon.RouteCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.VerificationContextKeys;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("RevocationTest — T070: revoked root key causes REVOKED_CREDENTIAL")
class RevocationTest {

  private static final HexFormat HEX = HexFormat.of();
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String SERVICE_NAME = "test-service";
  private static final String REQUEST_ROUTE = "/paid-resource";
  private static final String REQUEST_METHOD = "GET";

  private InMemoryRootKeyStore rootKeyStore;
  private InMemoryCredentialStore credentialStore;
  private byte[] rootKey;
  private byte[] tokenIdBytes;
  private byte[] preimageBytes;
  private byte[] paymentHash;
  private String authHeader;

  @BeforeEach
  void setUp() throws NoSuchAlgorithmException {
    rootKeyStore = new InMemoryRootKeyStore();
    credentialStore = new InMemoryCredentialStore();

    RootKeyStore.GenerationResult genResult = rootKeyStore.generateRootKey();
    rootKey = genResult.rootKey().value();
    tokenIdBytes = genResult.tokenId();

    preimageBytes = new byte[32];
    RANDOM.nextBytes(preimageBytes);
    paymentHash = MessageDigest.getInstance("SHA-256").digest(preimageBytes);

    MacaroonIdentifier identifier = new MacaroonIdentifier(1, paymentHash, tokenIdBytes);
    Macaroon macaroon = MacaroonMinter.mint(rootKey, identifier, null, boundaryCaveats());
    byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
    String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
    String preimageHex = HEX.formatHex(preimageBytes);
    authHeader = "L402 " + macaroonBase64 + ":" + preimageHex;
  }

  private List<Caveat> boundaryCaveats() {
    return List.of(
        new Caveat("route", REQUEST_ROUTE),
        new Caveat("method", REQUEST_METHOD),
        new Caveat(SERVICE_NAME + "_capabilities", "~"));
  }

  private List<CaveatVerifier> boundaryVerifiers() {
    return List.of(
        new RouteCaveatVerifier(10),
        new MethodCaveatVerifier(10),
        new CapabilitiesCaveatVerifier(SERVICE_NAME, 50));
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
  @DisplayName("baseline — valid root key")
  class Baseline {

    @Test
    @DisplayName("validation succeeds when root key is present")
    void validationSucceedsWithRootKeyPresent() {
      L402Validator validator =
          new L402Validator(rootKeyStore, credentialStore, boundaryVerifiers(), SERVICE_NAME);

      assertThatCode(() -> validator.validate(authHeader, boundaryContext()))
          .doesNotThrowAnyException();

      L402Validator.ValidationResult result = validator.validate(authHeader, boundaryContext());
      assertThat(result.credential()).isNotNull();
      assertThat(result.credential().tokenId()).isEqualTo(HEX.formatHex(tokenIdBytes));
    }
  }

  @Nested
  @DisplayName("revoked root key")
  class RevokedRootKey {

    @Test
    @DisplayName("throws REVOKED_CREDENTIAL after root key is revoked")
    void revokedRootKeyReturnsRevokedCredential() {
      rootKeyStore.revokeRootKey(tokenIdBytes);

      L402Validator validator =
          new L402Validator(rootKeyStore, credentialStore, boundaryVerifiers(), SERVICE_NAME);

      assertThatThrownBy(() -> validator.validate(authHeader, boundaryContext()))
          .isInstanceOf(L402Exception.class)
          .satisfies(
              ex -> {
                L402Exception l402Ex = (L402Exception) ex;
                assertThat(l402Ex.getErrorCode()).isEqualTo(ErrorCode.REVOKED_CREDENTIAL);
                assertThat(l402Ex.getMessage()).isEqualTo("Credential has been revoked");
              });
    }

    @Test
    @DisplayName("root-key-only revocation rejects exact replay and clears its cache slot")
    void validBeforeRevocationFailsAfter() {
      L402Validator validator =
          new L402Validator(rootKeyStore, credentialStore, boundaryVerifiers(), SERVICE_NAME);

      // Succeeds before revocation (credential gets cached)
      L402Validator.ValidationResult initial = validator.validate(authHeader, boundaryContext());
      initial.credential().destroy();
      assertThat(credentialStore.activeCount()).isEqualTo(1);

      // Revoke only the authoritative root key. The validator must reject the exact cache hit and
      // best-effort clear the stale cache slot itself.
      String tokenIdHex = HEX.formatHex(tokenIdBytes);
      rootKeyStore.revokeRootKey(tokenIdBytes);

      assertThatThrownBy(() -> validator.validate(authHeader, boundaryContext()))
          .isInstanceOf(L402Exception.class)
          .satisfies(
              ex -> {
                L402Exception l402Ex = (L402Exception) ex;
                assertThat(l402Ex.getErrorCode()).isEqualTo(ErrorCode.REVOKED_CREDENTIAL);
                assertThat(l402Ex.getMessage()).isEqualTo("Credential has been revoked");
              });
      assertThat(credentialStore.get(tokenIdHex)).isNull();
    }

    @Test
    @DisplayName("root key lookup failure never falls back to an exact cached credential")
    void rootKeyLookupFailureRejectsAndEvictsCachedCredential() {
      L402Validator validator =
          new L402Validator(rootKeyStore, credentialStore, boundaryVerifiers(), SERVICE_NAME);
      L402Validator.ValidationResult initial = validator.validate(authHeader, boundaryContext());
      initial.credential().destroy();

      RootKeyStore failingRootKeyStore =
          new RootKeyStore() {
            @Override
            public GenerationResult generateRootKey() {
              return rootKeyStore.generateRootKey();
            }

            @Override
            public SensitiveBytes getRootKey(byte[] keyId) {
              throw new IllegalStateException("ROOT-KEY-LOOKUP-DETAIL");
            }

            @Override
            public void revokeRootKey(byte[] keyId) {
              rootKeyStore.revokeRootKey(keyId);
            }
          };
      L402Validator failingValidator =
          new L402Validator(
              failingRootKeyStore, credentialStore, boundaryVerifiers(), SERVICE_NAME);

      assertThatThrownBy(() -> failingValidator.validate(authHeader, boundaryContext()))
          .isInstanceOf(L402Exception.class)
          .satisfies(
              ex -> {
                L402Exception l402Ex = (L402Exception) ex;
                assertThat(l402Ex.getErrorCode()).isEqualTo(ErrorCode.REVOKED_CREDENTIAL);
                assertThat(l402Ex.getMessage())
                    .isEqualTo("Credential has been revoked")
                    .doesNotContain("ROOT-KEY-LOOKUP-DETAIL");
              });
      assertThat(credentialStore.get(HEX.formatHex(tokenIdBytes))).isNull();
    }

    @Test
    @DisplayName("cache eviction failure does not mask sanitized revocation failure")
    void cacheEvictionFailureDoesNotMaskRevocation() {
      L402Validator validator =
          new L402Validator(rootKeyStore, credentialStore, boundaryVerifiers(), SERVICE_NAME);
      L402Validator.ValidationResult initial = validator.validate(authHeader, boundaryContext());
      initial.credential().destroy();
      rootKeyStore.revokeRootKey(tokenIdBytes);

      AtomicBoolean cacheRead = new AtomicBoolean(false);
      CredentialStore failingCredentialStore =
          new CredentialStore() {
            @Override
            public void store(String tokenId, L402Credential credential, long ttlSeconds) {
              credentialStore.store(tokenId, credential, ttlSeconds);
            }

            @Override
            public L402Credential get(String tokenId) {
              cacheRead.set(true);
              return credentialStore.get(tokenId);
            }

            @Override
            public void revoke(String tokenId) {
              throw new IllegalStateException("CACHE-EVICTION-DETAIL");
            }

            @Override
            public long activeCount() {
              return credentialStore.activeCount();
            }
          };
      L402Validator failingValidator =
          new L402Validator(
              rootKeyStore, failingCredentialStore, boundaryVerifiers(), SERVICE_NAME);

      assertThatThrownBy(() -> failingValidator.validate(authHeader, boundaryContext()))
          .isInstanceOf(L402Exception.class)
          .satisfies(
              ex -> {
                L402Exception l402Ex = (L402Exception) ex;
                assertThat(l402Ex.getErrorCode()).isEqualTo(ErrorCode.REVOKED_CREDENTIAL);
                assertThat(l402Ex.getMessage())
                    .isEqualTo("Credential has been revoked")
                    .doesNotContain("CACHE-EVICTION-DETAIL");
              });
      assertThat(cacheRead).isFalse();
    }
  }
}
