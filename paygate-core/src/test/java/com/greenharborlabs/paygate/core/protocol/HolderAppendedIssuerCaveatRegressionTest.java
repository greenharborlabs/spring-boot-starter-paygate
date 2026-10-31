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
import com.greenharborlabs.paygate.core.macaroon.MacaroonVerificationException;
import com.greenharborlabs.paygate.core.macaroon.MacaroonVerifier;
import com.greenharborlabs.paygate.core.macaroon.MethodCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.RootKeyStore;
import com.greenharborlabs.paygate.core.macaroon.RouteCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.ServicesCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.ValidUntilCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.VerificationContextKeys;
import com.greenharborlabs.paygate.core.macaroon.VerificationFailureReason;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Regression tests for presence-based issuer checks (security review 2026-10-18, SR-12 and I-03).
 *
 * <p>Issuer evidence, meaning the signed paid price and the mandatory boundaries, counts only when
 * it appears before the first {@code {svc}_valid_until}. A holder can only append, so appending a
 * missing issuer caveat after that point must not satisfy a presence check.
 */
class HolderAppendedIssuerCaveatRegressionTest {

  private static final HexFormat HEX = HexFormat.of();
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String SERVICE = "test-api";
  private static final String ROUTE = "/widgets/{id}";
  private static final String METHOD = "GET";

  private final Map<String, byte[]> roots = new HashMap<>();
  private final RootKeyStore rootKeyStore =
      new RootKeyStore() {
        @Override
        public GenerationResult generateRootKey() {
          throw new UnsupportedOperationException();
        }

        @Override
        public SensitiveBytes getRootKey(byte[] keyId) {
          byte[] stored = roots.get(HEX.formatHex(keyId));
          return stored == null ? null : new SensitiveBytes(stored.clone());
        }

        @Override
        public void revokeRootKey(byte[] keyId) {
          roots.remove(HEX.formatHex(keyId));
        }
      };

  private byte[] rootKey;
  private byte[] preimage;
  private byte[] paymentHash;
  private byte[] tokenId;

  @BeforeEach
  void setUp() throws Exception {
    rootKey = new byte[32];
    RANDOM.nextBytes(rootKey);
    preimage = new byte[32];
    RANDOM.nextBytes(preimage);
    paymentHash = MessageDigest.getInstance("SHA-256").digest(preimage);
    tokenId = new byte[32];
    RANDOM.nextBytes(tokenId);
    roots.put(HEX.formatHex(tokenId), rootKey.clone());
  }

  /** The caveat set minted by v0.1.5 L402Protocol (identifier v1, no price caveat). */
  private static List<Caveat> priceLessIssuerCaveats() {
    return List.of(
        new Caveat("services", SERVICE + ":0"),
        new Caveat("route", ROUTE),
        new Caveat("method", METHOD),
        new Caveat(SERVICE + "_capabilities", "~"),
        new Caveat(
            SERVICE + "_valid_until",
            Long.toString(Instant.now().plusSeconds(3600).getEpochSecond())));
  }

  @Test
  void requestDependentPriceLessCredentialStaysRejectedAfterHolderAppendsPrice() {
    AtomicInteger lookups = new AtomicInteger();
    L402Validator validator = validator(settledBackend(10, lookups));
    Macaroon legacy = mint(priceLessIssuerCaveats());

    assertThatThrownBy(
            () -> validator.validate(header(legacy), ctx("500", "REQUEST_DEPENDENT", null)))
        .isInstanceOf(PriceValidationException.class)
        .extracting(e -> ((PriceValidationException) e).kind())
        .isEqualTo(PriceValidationException.Kind.MISSING_PRICE_EVIDENCE);

    Macaroon forgedEvidence = attenuate(legacy, new Caveat(SERVICE + "_price_sats", "500"));
    assertThatThrownBy(
            () -> validator.validate(header(forgedEvidence), ctx("500", "REQUEST_DEPENDENT", null)))
        .isInstanceOf(PriceValidationException.class)
        .extracting(e -> ((PriceValidationException) e).kind())
        .isEqualTo(PriceValidationException.Kind.MISSING_PRICE_EVIDENCE);
    assertThat(lookups).hasValue(0);
  }

  @Test
  void routeStablePriceIncreaseCheckStillUsesInvoiceAfterHolderAppendsPrice() {
    AtomicInteger lookups = new AtomicInteger();
    L402Validator validator = validator(settledBackend(10, lookups));
    Macaroon legacy = mint(priceLessIssuerCaveats());

    assertThatThrownBy(() -> validator.validate(header(legacy), ctx("50", "ROUTE_STABLE", null)))
        .isInstanceOf(PriceValidationException.class)
        .extracting(e -> ((PriceValidationException) e).kind())
        .isEqualTo(PriceValidationException.Kind.ROUTE_STABLE_PRICE_INCREASE);
    int lookupsAfterBaseline = lookups.get();

    Macaroon forgedEvidence = attenuate(legacy, new Caveat(SERVICE + "_price_sats", "50"));
    assertThatThrownBy(
            () -> validator.validate(header(forgedEvidence), ctx("50", "ROUTE_STABLE", null)))
        .isInstanceOf(PriceValidationException.class)
        .extracting(e -> ((PriceValidationException) e).kind())
        .isEqualTo(PriceValidationException.Kind.ROUTE_STABLE_PRICE_INCREASE);
    assertThat(lookups.get()).isEqualTo(lookupsAfterBaseline + 1);
  }

  @Test
  void issuerCaveatThatIsPresentCannotBeWidened() {
    L402Validator validator =
        new L402Validator(rootKeyStore, new InMemoryCredentialStore(100), verifiers(), SERVICE);
    List<Caveat> caveats = new ArrayList<>(priceLessIssuerCaveats());
    caveats.add(caveats.size() - 1, new Caveat(SERVICE + "_price_sats", "10"));
    Macaroon issued = mint(caveats);
    Macaroon widened = attenuate(issued, new Caveat(SERVICE + "_price_sats", "500"));

    assertThatThrownBy(
            () -> validator.validate(header(widened), ctx("500", "REQUEST_DEPENDENT", null)))
        .isInstanceOf(L402Exception.class);
  }

  @Test
  void holderCannotSupplyCapabilityCeilingThatCustomIssuerOmitted() {
    L402Validator validator =
        new L402Validator(rootKeyStore, new InMemoryCredentialStore(100), verifiers(), SERVICE);
    List<Caveat> noCeiling = new ArrayList<>(priceLessIssuerCaveats());
    noCeiling.removeIf(c -> c.key().equals(SERVICE + "_capabilities"));
    Macaroon issued = mint(noCeiling);

    assertThatThrownBy(() -> validator.validate(header(issued), ctx(null, null, "admin")))
        .isInstanceOf(L402Exception.class)
        .extracting(e -> ((L402Exception) e).getErrorCode())
        .isEqualTo(ErrorCode.INVALID_SERVICE);

    Macaroon escalated = attenuate(issued, new Caveat(SERVICE + "_capabilities", "admin"));
    assertThatThrownBy(() -> validator.validate(header(escalated), ctx(null, null, "admin")))
        .isInstanceOf(L402Exception.class)
        .extracting(e -> ((L402Exception) e).getErrorCode())
        .isEqualTo(ErrorCode.INVALID_SERVICE);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"services", "route", "method", "capabilities"})
  void holderAppendedBoundaryAfterValidUntilCountsAsMissing(String omitted) {
    String omittedKey = omitted.equals("capabilities") ? SERVICE + "_capabilities" : omitted;
    List<Caveat> issuerCaveats = new ArrayList<>(priceLessIssuerCaveats());
    Caveat removed =
        issuerCaveats.stream().filter(c -> c.key().equals(omittedKey)).findFirst().orElseThrow();
    issuerCaveats.remove(removed);
    Macaroon filled = attenuate(mint(issuerCaveats), removed);
    L402Validator validator =
        new L402Validator(rootKeyStore, new InMemoryCredentialStore(100), verifiers(), SERVICE);

    assertThatThrownBy(() -> validator.validate(header(filled), ctx(null, null, null)))
        .isInstanceOf(L402Exception.class)
        .extracting(e -> ((L402Exception) e).getErrorCode())
        .isEqualTo(ErrorCode.INVALID_SERVICE);

    // The generic verifier applies the same issuer-prefix rule for the full L402 profile.
    assertThatThrownBy(
            () -> MacaroonVerifier.verify(filled, rootKey, verifiers(), ctx(null, null, null)))
        .isInstanceOfSatisfying(
            MacaroonVerificationException.class,
            e -> assertThat(e.getReason()).isEqualTo(VerificationFailureReason.CAVEAT_NOT_MET));
  }

  @Test
  void issuerBoundariesBeforeValidUntilStillVerifyGenerically() {
    Macaroon issued = mint(priceLessIssuerCaveats());
    MacaroonVerifier.verify(issued, rootKey, verifiers(), ctx(null, null, null));
  }

  // ---------------------------------------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------------------------------------

  private L402Validator validator(LightningBackend backend) {
    return new L402Validator(
        rootKeyStore, new InMemoryCredentialStore(100), verifiers(), SERVICE, backend);
  }

  private Macaroon mint(List<Caveat> caveats) {
    return MacaroonMinter.mint(
        rootKey, new MacaroonIdentifier(1, paymentHash, tokenId), null, caveats);
  }

  /** Holder-side attenuation: needs only the macaroon, not the root key. */
  private static Macaroon attenuate(Macaroon issued, Caveat caveat) {
    List<Caveat> caveats = new ArrayList<>(issued.caveats());
    caveats.add(caveat);
    byte[] sig =
        MacaroonCrypto.hmac(issued.signature(), caveat.toString().getBytes(StandardCharsets.UTF_8));
    return new Macaroon(issued.identifier(), issued.location(), caveats, sig);
  }

  private String header(Macaroon mac) {
    return "L402 "
        + Base64.getEncoder().encodeToString(MacaroonSerializer.serializeV2(mac))
        + ":"
        + HEX.formatHex(preimage);
  }

  private static List<CaveatVerifier> verifiers() {
    return List.of(
        new ServicesCaveatVerifier(10),
        new RouteCaveatVerifier(10),
        new MethodCaveatVerifier(10),
        new CapabilitiesCaveatVerifier(SERVICE, 50),
        new ValidUntilCaveatVerifier(SERVICE));
  }

  private static L402VerificationContext ctx(
      String currentPrice, String stability, String capability) {
    Map<String, String> md = new HashMap<>();
    md.put(VerificationContextKeys.REQUEST_ROUTE, ROUTE);
    md.put(VerificationContextKeys.REQUEST_METHOD, METHOD);
    md.put(VerificationContextKeys.REQUEST_PATH, "/widgets/1");
    if (currentPrice != null) {
      md.put(VerificationContextKeys.CURRENT_PRICE_SATS, currentPrice);
      md.put(VerificationContextKeys.PRICING_STABILITY, stability);
    }
    if (capability != null) {
      md.put(VerificationContextKeys.REQUESTED_CAPABILITY, capability);
    }
    return L402VerificationContext.builder()
        .serviceName(SERVICE)
        .currentTime(Instant.now())
        .requestMetadata(md)
        .build();
  }

  private LightningBackend settledBackend(long amount, AtomicInteger lookups) {
    return new LightningBackend() {
      @Override
      public Invoice createInvoice(long amountSats, String memo) {
        throw new UnsupportedOperationException();
      }

      @Override
      public Invoice lookupInvoice(byte[] hash) {
        lookups.incrementAndGet();
        return new Invoice(
            hash,
            "lnbc10legacy",
            amount,
            "legacy",
            InvoiceStatus.SETTLED,
            preimage,
            Instant.now().minusSeconds(60),
            Instant.now().plusSeconds(600));
      }

      @Override
      public boolean isHealthy() {
        return true;
      }
    };
  }
}
