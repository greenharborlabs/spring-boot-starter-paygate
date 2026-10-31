package com.greenharborlabs.paygate.core.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.greenharborlabs.paygate.api.crypto.SensitiveBytes;
import com.greenharborlabs.paygate.core.credential.InMemoryCredentialStore;
import com.greenharborlabs.paygate.core.lightning.Invoice;
import com.greenharborlabs.paygate.core.lightning.InvoiceStatus;
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
import com.greenharborlabs.paygate.core.macaroon.MacaroonVerifier;
import com.greenharborlabs.paygate.core.macaroon.MethodCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.PaidPriceCaveatVerifier;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Regression tests for issuer price evidence (security review 2026-10-18, SR-12).
 *
 * <p>Only a {@code {svc}_price_sats} caveat before the first {@code {svc}_valid_until} is issuer
 * evidence. A holder cannot turn a price-less credential into a priced one by appending a price
 * caveat, and a validator without an invoice-evidence source fails closed on price-less
 * credentials. All key material is generated per test with SecureRandom and never printed.
 */
class L402PriceEvidenceRegressionTest {

  private static final HexFormat HEX = HexFormat.of();
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String SERVICE = "svc";
  private static final String ROUTE = "/api/report/{id}";
  private static final String METHOD = "GET";
  private static final String PRICE_KEY = SERVICE + "_price_sats";

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

  @BeforeEach
  void setUp() throws Exception {
    credentialStore = new InMemoryCredentialStore(100, 0);
    rootKey = random(32);
    preimage = random(32);
    tokenId = random(32);
    byte[] paymentHash = MessageDigest.getInstance("SHA-256").digest(preimage);
    identifier = new MacaroonIdentifier(1, paymentHash, tokenId);
    rootKeys.put(HEX.formatHex(tokenId), rootKey.clone());
  }

  @AfterEach
  void tearDown() {
    credentialStore.close();
  }

  // ---------------------------------------------------------------------------------------------
  // Holder-appended price caveat on a price-less credential is not issuer evidence.
  // ---------------------------------------------------------------------------------------------

  @Test
  void priceLessCredentialIsRejectedOnRequestDependentRoute() {
    Macaroon legacy = mintWithoutPrice();
    L402Validator validator = validator(failingBackend(new AtomicInteger()));

    assertMissingPriceEvidence(validator, header(legacy), context("500", "REQUEST_DEPENDENT"));
  }

  @Test
  void holderAppendedPriceCaveatDoesNotSatisfyMissingEvidenceOnRequestDependentRoute() {
    Macaroon legacy = mintWithoutPrice();
    Macaroon forged = attenuate(legacy, new Caveat(PRICE_KEY, "1000000"));
    AtomicInteger lookups = new AtomicInteger();
    L402Validator validator = validator(failingBackend(lookups));

    assertMissingPriceEvidence(validator, header(forged), context("500", "REQUEST_DEPENDENT"));
    // Fresh validation caches before the price check (I-04), so repeat to cover the cached path.
    assertMissingPriceEvidence(validator, header(forged), context("999999", "REQUEST_DEPENDENT"));
    assertThat(lookups).hasValue(0);
  }

  @Test
  void routeStablePriceLessCredentialIsRejectedAfterPriceIncrease() {
    Macaroon legacy = mintWithoutPrice();
    AtomicInteger lookups = new AtomicInteger();
    L402Validator validator = validator(settledBackend(10, lookups));

    assertThatThrownBy(() -> validator.validate(header(legacy), context("50", "ROUTE_STABLE")))
        .isInstanceOfSatisfying(
            PriceValidationException.class,
            e ->
                assertThat(e.kind())
                    .isEqualTo(PriceValidationException.Kind.ROUTE_STABLE_PRICE_INCREASE));
    assertThat(lookups).hasValue(1);
  }

  @Test
  void holderAppendedPriceCaveatDoesNotSkipRouteStableInvoiceEvidence() {
    Macaroon legacy = mintWithoutPrice();
    Macaroon forged = attenuate(legacy, new Caveat(PRICE_KEY, "50"));
    AtomicInteger lookups = new AtomicInteger();
    // Invoice that was actually paid: 10 sats. Current price: 50 sats.
    L402Validator validator = validator(settledBackend(10, lookups));

    assertThatThrownBy(() -> validator.validate(header(forged), context("50", "ROUTE_STABLE")))
        .isInstanceOfSatisfying(
            PriceValidationException.class,
            e ->
                assertThat(e.kind())
                    .isEqualTo(PriceValidationException.Kind.ROUTE_STABLE_PRICE_INCREASE));
    assertThat(lookups).as("authoritative invoice evidence consulted").hasValue(1);
  }

  @Test
  void customIssuerPriceCaveatAfterValidUntilIsMissingPriceEvidence() {
    // A custom issuer that signs its price after valid_until cannot be told apart from a holder.
    List<Caveat> caveats = new ArrayList<>(boundaries());
    caveats.add(new Caveat(PRICE_KEY, "100"));
    Macaroon misordered = MacaroonMinter.mint(rootKey, identifier, null, caveats);
    L402Validator withBackend = validator(failingBackend(new AtomicInteger()));
    L402Validator withoutBackend =
        new L402Validator(rootKeyStore, credentialStore, verifiers(), SERVICE);

    assertMissingPriceEvidence(withBackend, header(misordered), context("10", "REQUEST_DEPENDENT"));
    assertMissingPriceEvidence(
        withoutBackend, header(misordered), context("10", "REQUEST_DEPENDENT"));
    assertMissingPriceEvidence(withoutBackend, header(misordered), context("10", "ROUTE_STABLE"));
  }

  // ---------------------------------------------------------------------------------------------
  // The four-argument constructor has no evidence source and fails closed.
  // ---------------------------------------------------------------------------------------------

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"REQUEST_DEPENDENT", "ROUTE_STABLE"})
  void backendlessConstructorRejectsPriceLessCredentialAtAnyPrice(String stability) {
    Macaroon legacy = mintWithoutPrice();
    L402Validator validator =
        new L402Validator(rootKeyStore, credentialStore, verifiers(), SERVICE);

    assertMissingPriceEvidence(validator, header(legacy), context("1", stability));
    assertMissingPriceEvidence(validator, header(legacy), context("1000000", stability));
  }

  // ---------------------------------------------------------------------------------------------
  // A holder-appended price never surfaces as verified, even without a current price.
  // ---------------------------------------------------------------------------------------------

  @Test
  void holderAppendedPriceIsNotVerifiedWhenNoCurrentPriceIsSupplied() {
    Macaroon legacy = mintWithoutPrice();
    Macaroon forged = attenuate(legacy, new Caveat(PRICE_KEY, "1000000"));
    L402Validator validator = validator(failingBackend(new AtomicInteger()));

    // Fresh validation, then the exact-variant cache hit.
    for (boolean expectFresh : new boolean[] {true, false}) {
      L402Validator.ValidationResult result = validator.validate(header(forged), unpricedContext());
      try {
        assertThat(result.freshValidation()).isEqualTo(expectFresh);
        assertThat(result.verifiedAttributes()).doesNotContainKey(PRICE_KEY);
        assertThat(result.verifiedAttributes()).containsKeys("route", "method");
        assertThat(result.paidPriceEvidence().coveredAmountSats()).isEmpty();
      } finally {
        result.credential().destroy();
      }
    }
  }

  @Test
  void holderAppendedPriceIsNotVerifiedByGenericCaveatVerification() {
    List<Caveat> caveats = new ArrayList<>(boundaries());
    caveats.add(new Caveat(PRICE_KEY, "1000000"));
    List<CaveatVerifier> withPrice = new ArrayList<>(verifiers());
    withPrice.add(new PaidPriceCaveatVerifier(SERVICE));

    assertThat(MacaroonVerifier.verifyCaveats(caveats, withPrice, unpricedContext()))
        .doesNotContainKey(PRICE_KEY)
        .containsKeys("route", "method");
  }

  @Test
  void issuerSignedPriceIsVerifiedWithoutCurrentPriceAndReflectsHolderNarrowing() {
    Macaroon issued = mint(new Caveat(PRICE_KEY, "10"));
    Macaroon narrowed = attenuate(issued, new Caveat(PRICE_KEY, "5"));
    L402Validator validator = validator(failingBackend(new AtomicInteger()));

    L402Validator.ValidationResult result = validator.validate(header(issued), unpricedContext());
    try {
      assertThat(result.verifiedAttributes()).containsEntry(PRICE_KEY, "10");
    } finally {
      result.credential().destroy();
    }
    L402Validator.ValidationResult narrowedResult =
        validator.validate(header(narrowed), unpricedContext());
    try {
      assertThat(narrowedResult.verifiedAttributes()).containsEntry(PRICE_KEY, "5");
    } finally {
      narrowedResult.credential().destroy();
    }
  }

  @Test
  void backendlessConstructorStillAcceptsIssuerSignedPrice() {
    Macaroon issued = mint(new Caveat(PRICE_KEY, "10"));
    L402Validator validator =
        new L402Validator(rootKeyStore, credentialStore, verifiers(), SERVICE);

    L402Validator.ValidationResult result =
        validator.validate(header(issued), context("10", "REQUEST_DEPENDENT"));
    try {
      assertThat(result.paidPriceEvidence().coveredAmountSats()).hasValue(10L);
    } finally {
      result.credential().destroy();
    }
    assertThatThrownBy(() -> validator.validate(header(issued), context("11", "REQUEST_DEPENDENT")))
        .isInstanceOfSatisfying(
            PriceValidationException.class,
            e -> assertThat(e.kind()).isEqualTo(PriceValidationException.Kind.INSUFFICIENT_PRICE));
  }

  // ---------------------------------------------------------------------------------------------
  // Controls: issuer-signed prices still validate, and holders may only lower them.
  // ---------------------------------------------------------------------------------------------

  @Test
  void holderCannotRaiseIssuerSignedPrice() {
    Macaroon issued = mint(new Caveat(PRICE_KEY, "10"));
    Macaroon forged = attenuate(issued, new Caveat(PRICE_KEY, "1000000"));
    L402Validator validator = validator(failingBackend(new AtomicInteger()));

    assertThatThrownBy(() -> validator.validate(header(forged), context("50", "REQUEST_DEPENDENT")))
        .isInstanceOf(L402Exception.class);
  }

  @Test
  void holderAppendedLowerPriceAfterValidUntilStillNarrowsCoverage() {
    Macaroon issued = mint(new Caveat(PRICE_KEY, "10"));
    Macaroon narrowed = attenuate(issued, new Caveat(PRICE_KEY, "5"));
    L402Validator validator = validator(failingBackend(new AtomicInteger()));

    L402Validator.ValidationResult result =
        validator.validate(header(narrowed), context("5", "REQUEST_DEPENDENT"));
    try {
      assertThat(result.paidPriceEvidence().coveredAmountSats()).hasValue(5L);
    } finally {
      result.credential().destroy();
    }
    assertThatThrownBy(
            () -> validator.validate(header(narrowed), context("10", "REQUEST_DEPENDENT")))
        .isInstanceOfSatisfying(
            PriceValidationException.class,
            e -> assertThat(e.kind()).isEqualTo(PriceValidationException.Kind.INSUFFICIENT_PRICE));
  }

  @Test
  void cacheHitRechecksPriceRouteMethodExpiryAndRevocation() {
    Macaroon issued = mint(new Caveat(PRICE_KEY, "10"));
    L402Validator validator = validator(failingBackend(new AtomicInteger()));
    validator.validate(header(issued), context("10", "REQUEST_DEPENDENT")).credential().destroy();

    assertThatThrownBy(() -> validator.validate(header(issued), context("11", "REQUEST_DEPENDENT")))
        .isInstanceOf(PriceValidationException.class);
    assertThatThrownBy(
            () ->
                validator.validate(
                    header(issued), context("/api/admin/{id}", METHOD, "10", "REQUEST_DEPENDENT")))
        .isInstanceOf(L402Exception.class);
    assertThatThrownBy(
            () ->
                validator.validate(
                    header(issued), context(ROUTE, "POST", "10", "REQUEST_DEPENDENT")))
        .isInstanceOf(L402Exception.class);
    // Cache entry survives request-specific mismatches.
    assertThat(credentialStore.activeCount()).isEqualTo(1);

    // Expiry is re-evaluated against request time even while cached.
    L402VerificationContext future =
        L402VerificationContext.builder()
            .serviceName(SERVICE)
            .currentTime(Instant.now().plusSeconds(7200))
            .requestMetadata(metadata(ROUTE, METHOD, "10", "REQUEST_DEPENDENT"))
            .build();
    assertThatThrownBy(() -> validator.validate(header(issued), future))
        .isInstanceOfSatisfying(
            L402Exception.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.EXPIRED_CREDENTIAL));

    validator.validate(header(issued), context("10", "REQUEST_DEPENDENT")).credential().destroy();
    rootKeyStore.revokeRootKey(tokenId);
    assertThatThrownBy(() -> validator.validate(header(issued), context("10", "REQUEST_DEPENDENT")))
        .isInstanceOfSatisfying(
            L402Exception.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.REVOKED_CREDENTIAL));
  }

  @Test
  void wrongPreimageNeverReachesCacheOrEvicts() {
    Macaroon issued = mint(new Caveat(PRICE_KEY, "10"));
    L402Validator validator = validator(failingBackend(new AtomicInteger()));
    validator.validate(header(issued), context("10", "REQUEST_DEPENDENT")).credential().destroy();

    byte[] wrong = random(32);
    // Different, signature-invalid variant + wrong preimage.
    Macaroon tampered = attenuate(issued, new Caveat("route", "/other"));
    assertThatThrownBy(
            () -> validator.validate(header(tampered, wrong), context("10", "REQUEST_DEPENDENT")))
        .isInstanceOfSatisfying(
            L402Exception.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_PREIMAGE));
    assertThatThrownBy(
            () -> validator.validate(header(issued, wrong), context("10", "REQUEST_DEPENDENT")))
        .isInstanceOfSatisfying(
            L402Exception.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_PREIMAGE));
    // Invalid-signature variant with a correct preimage does not evict the cached valid variant.
    Macaroon badSig =
        new Macaroon(issued.identifier(), issued.location(), issued.caveats(), random(32));
    assertThatThrownBy(() -> validator.validate(header(badSig), context("10", "REQUEST_DEPENDENT")))
        .isInstanceOf(L402Exception.class);
    L402Credential cached = credentialStore.get(HEX.formatHex(tokenId));
    try {
      assertThat(cached.macaroon()).isEqualTo(issued);
    } finally {
      cached.destroy();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------------------------------------

  private static void assertMissingPriceEvidence(
      L402Validator validator, String header, L402VerificationContext context) {
    assertThatThrownBy(() -> validator.validate(header, context))
        .isInstanceOfSatisfying(
            PriceValidationException.class,
            e ->
                assertThat(e.kind())
                    .isEqualTo(PriceValidationException.Kind.MISSING_PRICE_EVIDENCE));
  }

  private L402Validator validator(LightningBackend backend) {
    return new L402Validator(rootKeyStore, credentialStore, verifiers(), SERVICE, backend);
  }

  private static List<CaveatVerifier> verifiers() {
    return List.of(
        new ServicesCaveatVerifier(50),
        new RouteCaveatVerifier(50),
        new MethodCaveatVerifier(50),
        new CapabilitiesCaveatVerifier(SERVICE, 50),
        new ValidUntilCaveatVerifier(SERVICE));
  }

  /** First-party boundaries in built-in order, ending with {@code valid_until}. */
  private static List<Caveat> boundaries(Caveat... beforeCapabilities) {
    List<Caveat> caveats = new ArrayList<>();
    caveats.add(new Caveat("services", SERVICE + ":0"));
    caveats.add(new Caveat("route", ROUTE));
    caveats.add(new Caveat("method", METHOD));
    caveats.addAll(List.of(beforeCapabilities));
    caveats.add(new Caveat(SERVICE + "_capabilities", "~"));
    caveats.add(
        new Caveat(
            SERVICE + "_valid_until",
            Long.toString(Instant.now().plusSeconds(3600).getEpochSecond())));
    return caveats;
  }

  /** Mints a credential with the first-party boundaries exactly as L402Protocol does. */
  private Macaroon mint(Caveat... priceAndExtra) {
    return MacaroonMinter.mint(rootKey, identifier, null, boundaries(priceAndExtra));
  }

  /** Price-less issuance: identifier v1 and every mandatory boundary, but no price caveat. */
  private Macaroon mintWithoutPrice() {
    return mint();
  }

  private static Macaroon attenuate(Macaroon issued, Caveat caveat) {
    List<Caveat> caveats = new ArrayList<>(issued.caveats());
    caveats.add(caveat);
    byte[] signature =
        MacaroonCrypto.hmac(issued.signature(), caveat.toString().getBytes(StandardCharsets.UTF_8));
    return new Macaroon(issued.identifier(), issued.location(), caveats, signature);
  }

  private String header(Macaroon macaroon) {
    return header(macaroon, preimage);
  }

  private static String header(Macaroon macaroon, byte[] proof) {
    return "L402 "
        + Base64.getEncoder().encodeToString(MacaroonSerializer.serializeV2(macaroon))
        + ":"
        + HEX.formatHex(proof);
  }

  /** Request context without a current price, as compatibility callers supply it. */
  private static L402VerificationContext unpricedContext() {
    return L402VerificationContext.builder()
        .serviceName(SERVICE)
        .currentTime(Instant.now())
        .requestMetadata(
            Map.of(
                VerificationContextKeys.REQUEST_ROUTE, ROUTE,
                VerificationContextKeys.REQUEST_METHOD, METHOD))
        .build();
  }

  private static L402VerificationContext context(String price, String stability) {
    return context(ROUTE, METHOD, price, stability);
  }

  private static L402VerificationContext context(
      String route, String method, String price, String stability) {
    return L402VerificationContext.builder()
        .serviceName(SERVICE)
        .currentTime(Instant.now())
        .requestMetadata(metadata(route, method, price, stability))
        .build();
  }

  private static Map<String, String> metadata(
      String route, String method, String price, String stability) {
    return Map.of(
        VerificationContextKeys.REQUEST_ROUTE, route,
        VerificationContextKeys.REQUEST_METHOD, method,
        VerificationContextKeys.CURRENT_PRICE_SATS, price,
        VerificationContextKeys.PRICING_STABILITY, stability);
  }

  private LightningBackend settledBackend(long amountSats, AtomicInteger lookups) {
    return new LightningBackend() {
      @Override
      public Invoice createInvoice(long amount, String memo) {
        throw new UnsupportedOperationException();
      }

      @Override
      public Invoice lookupInvoice(byte[] hash) {
        lookups.incrementAndGet();
        return new Invoice(
            hash,
            "lnbc" + amountSats + "test",
            amountSats,
            "legacy",
            InvoiceStatus.SETTLED,
            preimage,
            Instant.now(),
            Instant.now().plusSeconds(60));
      }

      @Override
      public boolean isHealthy() {
        return true;
      }
    };
  }

  private static LightningBackend failingBackend(AtomicInteger lookups) {
    return new LightningBackend() {
      @Override
      public Invoice createInvoice(long amount, String memo) {
        throw new UnsupportedOperationException();
      }

      @Override
      public Invoice lookupInvoice(byte[] hash) {
        lookups.incrementAndGet();
        return null;
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
