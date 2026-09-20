package com.greenharborlabs.paygate.integration.wavelength;

import com.greenharborlabs.paygate.core.lightning.Invoice;
import com.greenharborlabs.paygate.core.lightning.InvoiceStatus;
import com.greenharborlabs.paygate.core.lightning.LightningTimeoutException;
import java.time.Duration;
import java.util.Map;

/** Ordered direct-vendor proof. No Spring adapter or protected-request assertion lives here. */
final class T7LiveHarness {
  interface Receiver {
    WavelengthWire.StatusResponse status();

    WavelengthInvoiceMapper.MappedReceive receive();

    Invoice lookup(byte[] hash);

    Map<String, Object> history(byte[] hash);

    void freshClient();
  }

  interface Control extends AutoCloseable {
    void restart();

    void faultOn();

    void faultOff();

    @Override
    void close();
  }

  interface Evidence {
    void passed(String gate, Map<String, Object> observation);

    void failed(String gate);
  }

  interface Payer {
    void payWithLostResponseAndReload(String invoice);
  }

  private final Receiver receiver;
  private final Control control;
  private final Payer payer;
  private final Evidence evidence;
  private final Duration wait;

  T7LiveHarness(Receiver receiver, Control control, Payer payer, Evidence evidence, Duration wait) {
    if (wait.isNegative() || wait.isZero() || wait.compareTo(Duration.ofSeconds(120)) > 0) {
      throw new IllegalArgumentException("Invalid spike wait bound");
    }
    this.receiver = receiver;
    this.control = control;
    this.payer = payer;
    this.evidence = evidence;
    this.wait = wait;
  }

  void execute() {
    String gate = "status";
    try {
      var before = ready();
      evidence.passed(gate, Map.of());
      gate = "receive";
      var received = receiver.receive();
      var invoice = received.invoice();
      require(invoice.amountSats() == 10 && invoice.status() == InvoiceStatus.PENDING);
      evidence.passed(gate, Map.of("receive_mode", received.mode().evidenceValue()));
      evidence.passed("invoice_decode", Map.of("principal_sats", 10));
      // Durable intent -> real dispatch / response dropped -> reload -> supported replay only.
      gate = "payer_recovery";
      payer.payWithLostResponseAndReload(invoice.bolt11());
      evidence.passed("payer_dispatch", Map.of("dispatch_count", 1));
      evidence.passed(gate, Map.of("dispatch_count", 1));
      gate = "receiver_settlement";
      var settled = settled(invoice.paymentHash());
      sameInvoice(invoice, settled, false);
      evidence.passed(gate, Map.of());
      gate = "custody";
      var receipt = receiver.history(invoice.paymentHash());
      require(receipt.containsKey("fee_sats"));
      var after = ready();
      evidence.passed(
          "custody",
          Map.of(
              "receive_mode",
              received.mode().evidenceValue(),
              "asset_category",
              received.mode().assetCategory(),
              "credit_delta_sats",
              after.balance().creditAvailableSats() - before.balance().creditAvailableSats(),
              "confirmed_delta_sats",
              after.balance().confirmedSats() - before.balance().confirmedSats(),
              "fee_sats",
              receipt.get("fee_sats"),
              "asset_control",
              received.mode() == WavelengthInvoiceMapper.ReceiveMode.CREDIT_BACKED
                  ? "server"
                  : "unknown",
              "redemption_dependency",
              received.mode() == WavelengthInvoiceMapper.ReceiveMode.CREDIT_BACKED
                  ? "server"
                  : "unknown",
              "exit_evidence",
              "unknown"));
      gate = "history";
      // Two additional unpaid receive requests only. No funded bulk history and no retries.
      receiver.receive();
      receiver.receive();
      var workload =
          new java.util.LinkedHashMap<String, Object>(receiver.history(invoice.paymentHash()));
      workload.put("unpaid_receives", 2);
      evidence.passed(gate, workload);
      gate = "restart_lookup";
      control.restart();
      receiver.freshClient();
      awaitReady(); // Operator unlock is out of band, bounded, and contains no password input.
      var reconstructed = receiver.lookup(invoice.paymentHash());
      sameInvoice(settled, reconstructed, true);
      var history = receiver.history(invoice.paymentHash());
      evidence.passed(gate, history);
      gate = "outage";
      control.faultOn();
      String readiness;
      try {
        readiness = receiver.status().ready() ? "ready" : "not_ready";
      } catch (WavelengthException | LightningTimeoutException failure) {
        readiness = "unavailable";
      }
      control.faultOff();
      awaitReady();
      evidence.passed(gate, Map.of("readiness", readiness));
    } catch (RuntimeException failure) {
      evidence.failed(gate);
      // Do not retain causes: SDK/HTTP/process exceptions can carry upstream bodies and paths.
      throw new IllegalStateException("Wavelength direct capability gate failed: " + gate);
    } finally {
      try {
        control.close();
        awaitReady();
        evidence.passed("restore", Map.of());
      } catch (RuntimeException failure) {
        evidence.failed("restore");
        throw new IllegalStateException("Wavelength owned state restoration failed");
      }
    }
  }

  private WavelengthWire.StatusResponse ready() {
    var status = receiver.status();
    require(status.ready() && status.unlocked() && "signet".equals(status.network()));
    return status;
  }

  private void awaitReady() {
    long deadline = System.nanoTime() + wait.toNanos();
    do {
      try {
        ready();
        if (System.nanoTime() >= deadline) break;
        return;
      } catch (WavelengthException
          | LightningTimeoutException
          | IllegalStateException unavailable) {
        // Only reads repeat; each RPC keeps its own tighter bound.
      }
      pause(deadline);
    } while (System.nanoTime() < deadline);
    throw new IllegalStateException("Spike out-of-band unlock or readiness deadline elapsed");
  }

  private Invoice settled(byte[] hash) {
    long deadline = System.nanoTime() + wait.toNanos();
    do {
      try {
        var invoice = receiver.lookup(hash);
        if (invoice.status() == InvoiceStatus.SETTLED && System.nanoTime() < deadline)
          return invoice;
        require(invoice.status() == InvoiceStatus.PENDING);
      } catch (LightningTimeoutException unavailable) {
        // A bounded read timeout does not establish that settlement failed.
      } catch (WavelengthUpstreamException unavailable) {
        if (unavailable.classification()
            != WavelengthClient.FailureClassification.TEMPORARY_UNAVAILABLE) {
          throw unavailable;
        }
      }
      pause(deadline);
    } while (System.nanoTime() < deadline);
    throw new IllegalStateException("Spike receiver settlement deadline elapsed");
  }

  private static void pause(long deadline) {
    try {
      long remaining = deadline - System.nanoTime();
      if (remaining > 0) Thread.sleep(Duration.ofNanos(Math.min(remaining, 500_000_000L)));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Spike wait interrupted");
    }
  }

  static void sameInvoice(Invoice expected, Invoice actual, boolean compareSettled) {
    require(actual.status() == InvoiceStatus.SETTLED);
    // Avoid assertion libraries: Invoice.toString includes the live BOLT11.
    require(
        expected.bolt11().equals(actual.bolt11())
            && expected.amountSats() == actual.amountSats()
            && java.util.Objects.equals(expected.memo(), actual.memo())
            && expected.createdAt().equals(actual.createdAt())
            && expected.expiresAt().equals(actual.expiresAt())
            && com.greenharborlabs.paygate.core.macaroon.MacaroonCrypto.constantTimeEquals(
                expected.paymentHash(), actual.paymentHash()));
    if (compareSettled) require(expected.equals(actual));
  }

  private static void require(boolean value) {
    if (!value) throw new IllegalStateException("Inconsistent direct capability evidence");
  }
}
