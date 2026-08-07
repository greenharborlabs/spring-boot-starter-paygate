package com.greenharborlabs.paygate.core.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                assertThat(l402Ex.getMessage()).contains("No root key found");
              });
    }

    @Test
    @DisplayName("validation succeeds before revocation but fails after credential store eviction")
    void validBeforeRevocationFailsAfter() {
      L402Validator validator =
          new L402Validator(rootKeyStore, credentialStore, boundaryVerifiers(), SERVICE_NAME);

      // Succeeds before revocation (credential gets cached)
      assertThatCode(() -> validator.validate(authHeader, boundaryContext()))
          .doesNotThrowAnyException();

      // Revoke: both root key and credential store must be cleared.
      // The validator no longer re-checks root key on the cached path for performance;
      // callers revoking keys should also evict from the credential store.
      String tokenIdHex = HEX.formatHex(tokenIdBytes);
      credentialStore.revoke(tokenIdHex);
      rootKeyStore.revokeRootKey(tokenIdBytes);

      // Fails after revocation — falls through to full validation which finds no root key
      assertThatThrownBy(() -> validator.validate(authHeader, boundaryContext()))
          .isInstanceOf(L402Exception.class)
          .satisfies(
              ex -> {
                L402Exception l402Ex = (L402Exception) ex;
                assertThat(l402Ex.getErrorCode()).isEqualTo(ErrorCode.REVOKED_CREDENTIAL);
              });
    }
  }
}
