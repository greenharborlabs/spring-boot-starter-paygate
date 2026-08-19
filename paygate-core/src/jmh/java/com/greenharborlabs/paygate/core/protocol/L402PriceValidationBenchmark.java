package com.greenharborlabs.paygate.core.protocol;

import com.greenharborlabs.paygate.api.crypto.SensitiveBytes;
import com.greenharborlabs.paygate.core.credential.InMemoryCredentialStore;
import com.greenharborlabs.paygate.core.lightning.Invoice;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
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
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;

/**
 * Measures warmed exact-cache-hit reuse of a current-format, price-evidenced route-stable L402
 * credential. The fixture asserts that this local fast path never performs invoice lookup.
 */
@BenchmarkMode({Mode.SampleTime, Mode.Throughput})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Threads(4)
public class L402PriceValidationBenchmark {

  private static final String SERVICE_NAME = "benchmark-service";
  private static final String ROUTE = "/api/v1/widgets/{id}";
  private static final String METHOD = "GET";

  private final AtomicLong invoiceLookups = new AtomicLong();
  private L402Validator validator;
  private String authorizationHeader;
  private L402VerificationContext context;

  /** Prepares and warms the exact signed credential that every benchmark operation reuses. */
  @Setup(Level.Trial)
  public void setup() throws Exception {
    byte[] rootKey = new byte[32];
    byte[] preimage = new byte[32];
    byte[] tokenId = new byte[32];
    for (int i = 0; i < rootKey.length; i++) {
      rootKey[i] = (byte) 0x11;
      preimage[i] = (byte) 0x22;
      tokenId[i] = (byte) 0x33;
    }
    byte[] paymentHash = MessageDigest.getInstance("SHA-256").digest(preimage);
    String tokenIdHex = HexFormat.of().formatHex(tokenId);
    RootKeyStore rootKeyStore =
        new RootKeyStore() {
          @Override
          public GenerationResult generateRootKey() {
            throw new UnsupportedOperationException();
          }

          @Override
          public SensitiveBytes getRootKey(byte[] ignored) {
            return new SensitiveBytes(rootKey.clone());
          }

          @Override
          public void revokeRootKey(byte[] ignored) {}
        };
    LightningBackend backend =
        new LightningBackend() {
          @Override
          public Invoice createInvoice(long amountSats, String memo) {
            throw new UnsupportedOperationException();
          }

          @Override
          public Invoice lookupInvoice(byte[] paymentHash) {
            invoiceLookups.incrementAndGet();
            throw new AssertionError("evidenced cache hits must not look up invoices");
          }

          @Override
          public boolean isHealthy() {
            return true;
          }
        };
    List<CaveatVerifier> verifiers =
        List.of(
            new ServicesCaveatVerifier(10),
            new RouteCaveatVerifier(10),
            new MethodCaveatVerifier(10),
            new CapabilitiesCaveatVerifier(SERVICE_NAME, 10),
            new ValidUntilCaveatVerifier(SERVICE_NAME));
    validator =
        new L402Validator(
            rootKeyStore, new InMemoryCredentialStore(), verifiers, SERVICE_NAME, backend);
    Macaroon macaroon =
        MacaroonMinter.mint(
            rootKey,
            new MacaroonIdentifier(1, paymentHash, tokenId),
            null,
            List.of(
                new Caveat("services", SERVICE_NAME + ":0"),
                new Caveat("route", ROUTE),
                new Caveat("method", METHOD),
                new Caveat(SERVICE_NAME + "_price_sats", "100"),
                new Caveat(SERVICE_NAME + "_capabilities", "~"),
                new Caveat(
                    SERVICE_NAME + "_valid_until",
                    Long.toString(Instant.now().plusSeconds(3600).getEpochSecond()))));
    authorizationHeader =
        "L402 "
            + Base64.getEncoder().encodeToString(MacaroonSerializer.serializeV2(macaroon))
            + ":"
            + HexFormat.of().formatHex(preimage);
    context =
        L402VerificationContext.builder()
            .serviceName(SERVICE_NAME)
            .currentTime(Instant.now())
            .requestMetadata(
                Map.of(
                    VerificationContextKeys.REQUEST_ROUTE,
                    ROUTE,
                    VerificationContextKeys.REQUEST_METHOD,
                    METHOD,
                    VerificationContextKeys.CURRENT_PRICE_SATS,
                    "100",
                    VerificationContextKeys.PRICING_STABILITY,
                    "ROUTE_STABLE"))
            .build();
    L402Validator.ValidationResult warmed = validator.validate(authorizationHeader, context);
    warmed.credential().destroy();
  }

  /** Validates the warmed credential through the exact cache-hit path. */
  @Benchmark
  public void validateExactCacheHit() {
    L402Validator.ValidationResult result = validator.validate(authorizationHeader, context);
    result.credential().destroy();
  }

  /** Fails the benchmark if the signed-evidence fast path ever consulted invoice state. */
  @TearDown(Level.Iteration)
  public void assertNoInvoiceLookups() {
    if (invoiceLookups.get() != 0) {
      throw new AssertionError("evidenced cache-hit path performed invoice lookup");
    }
  }
}
