package com.greenharborlabs.paygate.core.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.greenharborlabs.paygate.api.crypto.SensitiveBytes;
import com.greenharborlabs.paygate.core.credential.InMemoryCredentialStore;
import com.greenharborlabs.paygate.core.lightning.Invoice;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.core.macaroon.CapabilitiesCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.Caveat;
import com.greenharborlabs.paygate.core.macaroon.CaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.L402VerificationContext;
import com.greenharborlabs.paygate.core.macaroon.Macaroon;
import com.greenharborlabs.paygate.core.macaroon.MacaroonCrypto;
import com.greenharborlabs.paygate.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.paygate.core.macaroon.MacaroonMinter;
import com.greenharborlabs.paygate.core.macaroon.MacaroonSerializer;
import com.greenharborlabs.paygate.core.macaroon.MethodCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.RootKeyStore;
import com.greenharborlabs.paygate.core.macaroon.RouteCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.ServicesCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.ValidUntilCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.VerificationContextKeys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for 2026-10-18 security review items I-04 (the credential cache is written only
 * after the paid-price check) and I-01 (L402 headers accept only canonical padded base64).
 *
 * <p>All key material is generated per test with SecureRandom and never printed.
 */
class L402ValidatorCacheAndEncodingRegressionTest {

  private static final HexFormat HEX = HexFormat.of();
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String SERVICE = "svc";
  private static final String ROUTE = "/api/report/{id}";
  private static final String METHOD = "GET";
  private static final String BASE64_ALPHABET =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

  private final Map<String, byte[]> rootKeys = new HashMap<>();
  private final RootKeyStore rootKeyStore =
      new RootKeyStore() {
        @Override
        public GenerationResult generateRootKey() {
          throw new UnsupportedOperationException();
        }

        @Override
        public SensitiveBytes getRootKey(byte[] keyId) {
          byte[] key = rootKeys.get(HEX.formatHex(keyId));
          return key == null ? null : new SensitiveBytes(key.clone());
        }

        @Override
        public void revokeRootKey(byte[] keyId) {
          rootKeys.remove(HEX.formatHex(keyId));
        }
      };

  private InMemoryCredentialStore credentialStore;
  private byte[] rootKey;
  private byte[] preimage;
  private byte[] tokenId;
  private MacaroonIdentifier identifier;
  private L402Validator validator;

  @BeforeEach
  void setUp() throws Exception {
    credentialStore = new InMemoryCredentialStore(100, 0);
    rootKey = random(32);
    preimage = random(32);
    tokenId = random(32);
    byte[] paymentHash = MessageDigest.getInstance("SHA-256").digest(preimage);
    identifier = new MacaroonIdentifier(1, paymentHash, tokenId);
    rootKeys.put(HEX.formatHex(tokenId), rootKey.clone());
    validator =
        new L402Validator(rootKeyStore, credentialStore, verifiers(), SERVICE, unusedBackend());
  }

  @AfterEach
  void tearDown() {
    credentialStore.close();
  }

  @Nested
  @DisplayName("I-04: cache insertion after the paid-price check")
  class CacheOrdering {

    @Test
    @DisplayName("a price-failing same-token variant does not replace the cached valid variant")
    void priceFailingVariantDoesNotReplaceCachedValidVariant() {
      Macaroon issued = mint(new Caveat(SERVICE + "_price_sats", "10"));
      validator.validate(header(issued), context("10")).credential().destroy();

      // Holder attenuates the price downward; the variant is signature-valid but underpays.
      Macaroon underpaying = attenuate(issued, new Caveat(SERVICE + "_price_sats", "5"));
      assertThatThrownBy(() -> validator.validate(header(underpaying), context("10")))
          .isInstanceOfSatisfying(
              PriceValidationException.class,
              e ->
                  assertThat(e.kind()).isEqualTo(PriceValidationException.Kind.INSUFFICIENT_PRICE));

      L402Credential cached = credentialStore.get(HEX.formatHex(tokenId));
      try {
        assertThat(cached.macaroon()).isEqualTo(issued).isNotEqualTo(underpaying);
      } finally {
        cached.destroy();
      }
      L402Validator.ValidationResult again = validator.validate(header(issued), context("10"));
      try {
        assertThat(again.freshValidation()).as("original variant is still a cache hit").isFalse();
      } finally {
        again.credential().destroy();
      }
    }

    @Test
    @DisplayName("a fresh credential that fails the price check is never cached")
    void freshPriceFailureIsNotCached() {
      Macaroon issued = mint(new Caveat(SERVICE + "_price_sats", "10"));

      assertThatThrownBy(() -> validator.validate(header(issued), context("11")))
          .isInstanceOf(PriceValidationException.class);

      assertThat(credentialStore.activeCount()).isZero();
      L402Validator.ValidationResult later = validator.validate(header(issued), context("10"));
      try {
        assertThat(later.freshValidation()).isTrue();
      } finally {
        later.credential().destroy();
      }
      assertThat(credentialStore.activeCount()).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("I-01: canonical padded base64 only")
  class CanonicalBase64 {

    @Test
    @DisplayName("an unpadded header is rejected as malformed and not cached")
    void unpaddedHeaderIsRejected() {
      Macaroon issued = mintNeedingPadding();
      String padded = header(issued);
      String b64 = macaroonBase64(padded);
      assertThat(b64).endsWith("=");
      String unpadded = "L402 " + b64.replace("=", "") + proof(padded);

      assertMalformed(unpadded);
      assertThat(credentialStore.activeCount()).isZero();
    }

    @Test
    @DisplayName(
        "nonzero unused trailing bits are rejected even after the canonical form is cached")
    void nonzeroTrailingBitsAreRejected() {
      Macaroon issued = mintNeedingPadding();
      String padded = header(issued);
      validator.validate(padded, context("10")).credential().destroy();

      String b64 = macaroonBase64(padded);
      int lastData = b64.replace("=", "").length() - 1;
      char flipped = BASE64_ALPHABET.charAt(BASE64_ALPHABET.indexOf(b64.charAt(lastData)) ^ 1);
      String nonCanonical =
          "L402 "
              + b64.substring(0, lastData)
              + flipped
              + b64.substring(lastData + 1)
              + proof(padded);
      assertThat(nonCanonical).isNotEqualTo(padded);
      assertThat(Base64.getDecoder().decode(macaroonBase64(nonCanonical)))
          .as("the JDK decoder maps both spellings to the same bytes")
          .isEqualTo(Base64.getDecoder().decode(b64));

      assertMalformed(nonCanonical);
    }

    @Test
    @DisplayName("the canonical padded header and the legacy LSAT prefix are still accepted")
    void canonicalHeaderIsAccepted() {
      Macaroon issued = mintNeedingPadding();
      String padded = header(issued);

      L402Validator.ValidationResult result = validator.validate(padded, context("10"));
      try {
        assertThat(result.freshValidation()).isTrue();
      } finally {
        result.credential().destroy();
      }
      validator.validate("lsat " + padded.substring(5), context("10")).credential().destroy();
    }

    @Test
    @DisplayName("L402Credential.parse rejects missing padding directly")
    void credentialParseRejectsMissingPadding() {
      String padded = header(mintNeedingPadding());
      String unpadded = "L402 " + macaroonBase64(padded).replace("=", "") + proof(padded);

      assertThatThrownBy(() -> L402Credential.parse(unpadded))
          .isInstanceOfSatisfying(
              L402Exception.class,
              e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MALFORMED_HEADER));
    }

    private void assertMalformed(String header) {
      assertThatThrownBy(() -> validator.validate(header, context("10")))
          .isInstanceOfSatisfying(
              L402Exception.class,
              e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MALFORMED_HEADER));
    }
  }

  private Macaroon mintNeedingPadding() {
    // Vary a caveat value length until the serialized form needs base64 padding.
    for (int pad = 0; pad < 3; pad++) {
      Macaroon m =
          mint(new Caveat(SERVICE + "_price_sats", "10"), new Caveat("x" + "y".repeat(pad), "z"));
      if (MacaroonSerializer.serializeV2(m).length % 3 != 0) {
        return m;
      }
    }
    throw new AssertionError("unreachable");
  }

  private static String macaroonBase64(String header) {
    return header.substring("L402 ".length(), header.indexOf(':'));
  }

  private static String proof(String header) {
    return header.substring(header.indexOf(':'));
  }

  private static List<CaveatVerifier> verifiers() {
    return List.of(
        new ServicesCaveatVerifier(50),
        new RouteCaveatVerifier(50),
        new MethodCaveatVerifier(50),
        new CapabilitiesCaveatVerifier(SERVICE, 50),
        new ValidUntilCaveatVerifier(SERVICE));
  }

  /** Mints a credential with the first-party boundaries exactly as L402Protocol does. */
  private Macaroon mint(Caveat... priceAndExtra) {
    List<Caveat> caveats = new ArrayList<>();
    caveats.add(new Caveat("services", SERVICE + ":0"));
    caveats.add(new Caveat("route", ROUTE));
    caveats.add(new Caveat("method", METHOD));
    caveats.addAll(List.of(priceAndExtra));
    caveats.add(new Caveat(SERVICE + "_capabilities", "~"));
    caveats.add(
        new Caveat(
            SERVICE + "_valid_until",
            Long.toString(Instant.now().plusSeconds(3600).getEpochSecond())));
    return MacaroonMinter.mint(rootKey, identifier, null, caveats);
  }

  private static Macaroon attenuate(Macaroon issued, Caveat caveat) {
    List<Caveat> caveats = new ArrayList<>(issued.caveats());
    caveats.add(caveat);
    byte[] signature =
        MacaroonCrypto.hmac(issued.signature(), caveat.toString().getBytes(StandardCharsets.UTF_8));
    return new Macaroon(issued.identifier(), issued.location(), caveats, signature);
  }

  private String header(Macaroon macaroon) {
    return "L402 "
        + Base64.getEncoder().encodeToString(MacaroonSerializer.serializeV2(macaroon))
        + ":"
        + HEX.formatHex(preimage);
  }

  private static L402VerificationContext context(String price) {
    return L402VerificationContext.builder()
        .serviceName(SERVICE)
        .currentTime(Instant.now())
        .requestMetadata(
            Map.of(
                VerificationContextKeys.REQUEST_ROUTE, ROUTE,
                VerificationContextKeys.REQUEST_METHOD, METHOD,
                VerificationContextKeys.CURRENT_PRICE_SATS, price,
                VerificationContextKeys.PRICING_STABILITY, "REQUEST_DEPENDENT"))
        .build();
  }

  /** Issuer-priced credentials never need invoice evidence, so the backend must stay unused. */
  private static LightningBackend unusedBackend() {
    return new LightningBackend() {
      @Override
      public Invoice createInvoice(long amount, String memo) {
        throw new UnsupportedOperationException();
      }

      @Override
      public Invoice lookupInvoice(byte[] hash) {
        throw new AssertionError("issuer-priced validation must not consult the backend");
      }

      @Override
      public boolean isHealthy() {
        return false;
      }
    };
  }

  private static byte[] random(int n) {
    byte[] b = new byte[n];
    RANDOM.nextBytes(b);
    return b;
  }
}
