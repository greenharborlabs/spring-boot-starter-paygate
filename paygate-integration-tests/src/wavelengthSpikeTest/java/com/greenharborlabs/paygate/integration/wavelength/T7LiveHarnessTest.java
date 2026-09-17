package com.greenharborlabs.paygate.integration.wavelength;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.greenharborlabs.paygate.core.lightning.Invoice;
import com.greenharborlabs.paygate.core.lightning.InvoiceStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("T7 bounded direct-vendor orchestration (synthetic only)")
class T7LiveHarnessTest {
  private final List<String> events = new ArrayList<>();
  private final FakeReceiver receiver = new FakeReceiver();
  private final FakeControl control = new FakeControl();
  private boolean payerFails;

  @Test
  void provesOrderedFlowUsingFreshReceiverAndOnlyThreeReceives() {
    harness().execute();
    assertThat(receiver.receives).isEqualTo(3);
    assertThat(events)
        .containsSubsequence(
            "payer",
            "passed:receiver_settlement",
            "passed:history",
            "restart",
            "fresh",
            "passed:restart_lookup",
            "fault-on",
            "fault-off",
            "restore",
            "passed:restore");
    assertThat(events).containsOnlyOnce("payer", "restart", "fresh", "fault-on", "restore");
  }

  @Test
  void failedPayerStopsAllDependentWorkAndRestoresWithoutRetry() {
    payerFails = true;
    assertThatThrownBy(() -> harness().execute())
        .hasMessage("Wavelength direct capability gate failed: payer_recovery")
        .hasNoCause();
    assertThat(receiver.receives).isEqualTo(1);
    assertThat(events)
        .contains("failed:payer_recovery", "restore")
        .doesNotContain("restart", "fault-on");
  }

  @Test
  void inEnvelopeReconstructionFailureStopsOutage() {
    receiver.changedAfterRestart = true;
    assertThatThrownBy(() -> harness().execute())
        .hasMessage("Wavelength direct capability gate failed: restart_lookup")
        .hasNoCause();
    assertThat(events).contains("failed:restart_lookup", "restore").doesNotContain("fault-on");
  }

  @Test
  void failedFaultActivationStillRestores() {
    control.faultFails = true;
    assertThatThrownBy(() -> harness().execute())
        .hasMessage("Wavelength direct capability gate failed: outage");
    assertThat(events).contains("fault-on", "failed:outage", "restore");
  }

  @Test
  void restorationFailureCannotProduceSuccess() {
    control.restoreFails = true;
    assertThatThrownBy(() -> harness().execute())
        .hasMessage("Wavelength owned state restoration failed")
        .hasNoCause();
    assertThat(events).contains("failed:restore").doesNotContain("passed:restore");
  }

  @Test
  void receiverPendingWaitIsBoundedAndDoesNotRestart() {
    receiver.pending = true;
    assertThatThrownBy(() -> harness().execute())
        .hasMessage("Wavelength direct capability gate failed: receiver_settlement");
    assertThat(events).contains("failed:receiver_settlement", "restore").doesNotContain("restart");
  }

  @Test
  void lookupReceivesOnlyHashAfterFreshClient() {
    harness().execute();
    assertThat(receiver.fresh).isTrue();
    assertThat(receiver.freshLookups).isEqualTo(1);
  }

  @Test
  void missingFeeStopsBeforeHistoryWorkloadAndRestart() {
    receiver.missingFee = true;
    assertThatThrownBy(() -> harness().execute())
        .hasMessage("Wavelength direct capability gate failed: custody");
    assertThat(receiver.receives).isEqualTo(1);
    assertThat(events).contains("failed:custody", "restore").doesNotContain("restart", "fault-on");
  }

  @Test
  void unknownReceiveModeDoesNotBecomeSelfCustodyEvidence() {
    var mode = WavelengthInvoiceMapper.ReceiveMode.SWAP_BACKED;
    assertThat(mode.assetCategory()).isEqualTo("unknown");
  }

  @Test
  void fixedComparisonFailureDoesNotRenderInvoice() {
    assertThatThrownBy(
            () ->
                T7LiveHarness.sameInvoice(
                    invoice(InvoiceStatus.SETTLED, "original"),
                    invoice(InvoiceStatus.SETTLED, "changed"),
                    true))
        .hasMessage("Inconsistent direct capability evidence")
        .hasNoCause();
  }

  @Test
  void missingOperatorSetupReportsNamesOnly() {
    assertThatThrownBy(() -> T7LiveEnvironment.preflight(Map.of()))
        .hasMessageContaining(
            "WAVELENGTH_SPIKE_DAEMON_PID_FILE", "WAVELENGTH_SPIKE_CONTROL_REVIEWED")
        .hasNoCause();
  }

  private T7LiveHarness harness() {
    return new T7LiveHarness(
        receiver,
        control,
        ignored -> {
          events.add("payer");
          if (payerFails) throw new IllegalStateException("sensitive upstream error");
        },
        new T7LiveHarness.Evidence() {
          public void passed(String gate, Map<String, Object> observation) {
            events.add("passed:" + gate);
          }

          public void failed(String gate) {
            events.add("failed:" + gate);
          }
        },
        Duration.ofMillis(20));
  }

  private static Invoice invoice(InvoiceStatus status, String memo) {
    return new Invoice(
        new byte[32],
        "synthetic-invoice-not-live",
        10,
        memo,
        status,
        null,
        Instant.ofEpochSecond(1),
        Instant.ofEpochSecond(3601));
  }

  private final class FakeReceiver implements T7LiveHarness.Receiver {
    private int receives;
    private int freshLookups;
    private boolean fresh;
    private boolean changedAfterRestart;
    private boolean pending;
    private boolean missingFee;

    public WavelengthWire.StatusResponse status() {
      return new WavelengthWire.StatusResponse(
          true,
          true,
          "signet",
          new WavelengthWire.BalanceResponse(0, 0, 0, receives == 0 ? 0 : 10, 0),
          0);
    }

    public WavelengthInvoiceMapper.MappedReceive receive() {
      receives++;
      return new WavelengthInvoiceMapper.MappedReceive(
          invoice(InvoiceStatus.PENDING, "memo"),
          WavelengthInvoiceMapper.ReceiveMode.CREDIT_BACKED);
    }

    public Invoice lookup(byte[] hash) {
      if (fresh) freshLookups++;
      return invoice(
          pending ? InvoiceStatus.PENDING : InvoiceStatus.SETTLED,
          fresh && changedAfterRestart ? "changed" : "memo");
    }

    public Map<String, Object> history(byte[] hash) {
      return missingFee
          ? Map.of("history_entries", 3)
          : Map.of("history_entries", 3, "fee_sats", 0);
    }

    public void freshClient() {
      events.add("fresh");
      fresh = true;
    }
  }

  private final class FakeControl implements T7LiveHarness.Control {
    private boolean faultFails;
    private boolean restoreFails;

    public void restart() {
      events.add("restart");
    }

    public void faultOn() {
      events.add("fault-on");
      if (faultFails) throw new IllegalStateException();
    }

    public void faultOff() {
      events.add("fault-off");
    }

    public void close() {
      events.add("restore");
      if (restoreFails) throw new IllegalStateException();
    }
  }
}
