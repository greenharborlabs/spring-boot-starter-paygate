package com.greenharborlabs.paygate.integration.wavelength;

import com.greenharborlabs.paygate.core.lightning.InvoiceStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;

/** Validates Wavelength status evidence and applies the conservative Paygate mapping table. */
final class WavelengthEntryStatusMapper {

  private static final String PENDING = "ENTRY_STATUS_PENDING";
  private static final String COMPLETE = "ENTRY_STATUS_COMPLETE";
  private static final String FAILED = "ENTRY_STATUS_FAILED";
  private static final String EXPIRED = "ENTRY_FAILURE_CODE_EXPIRED";
  private static final Set<String> TERMINAL_FAILURE_CODES =
      Set.of(
          "ENTRY_FAILURE_CODE_TIMED_OUT",
          "ENTRY_FAILURE_CODE_REFUNDED",
          "ENTRY_FAILURE_CODE_NEEDS_INTERVENTION",
          "ENTRY_FAILURE_CODE_FAILED");
  private static final Set<String> PENDING_PHASES =
      Set.of(
          "WALLET_ENTRY_PHASE_REQUEST_CREATED",
          "WALLET_ENTRY_PHASE_WAITING_FOR_PAYMENT",
          "WALLET_ENTRY_PHASE_PAYMENT_DETECTED",
          "WALLET_ENTRY_PHASE_SETTLING");
  private static final Set<String> FAILED_PHASES =
      Set.of("WALLET_ENTRY_PHASE_FAILED", "WALLET_ENTRY_PHASE_REFUNDED");

  private final Clock clock;

  WavelengthEntryStatusMapper(Clock clock) {
    this.clock = clock;
  }

  InvoiceStatus validateAndMap(WavelengthWire.WalletEntry entry, Instant expiresAt) {
    return switch (entry.status()) {
      case PENDING -> mapPending(entry, expiresAt);
      case COMPLETE -> mapComplete(entry);
      case FAILED -> mapFailed(entry, expiresAt);
      default -> throw protocolFailure();
    };
  }

  private InvoiceStatus mapPending(WavelengthWire.WalletEntry entry, Instant expiresAt) {
    if (entry.failureCode().isPresent() || !PENDING_PHASES.contains(entry.progress().phase())) {
      throw protocolFailure();
    }
    return clock.instant().isBefore(expiresAt) ? InvoiceStatus.PENDING : InvoiceStatus.EXPIRED;
  }

  private static InvoiceStatus mapComplete(WavelengthWire.WalletEntry entry) {
    if (entry.failureCode().isPresent()
        || !"WALLET_ENTRY_PHASE_CONFIRMED".equals(entry.progress().phase())) {
      throw protocolFailure();
    }
    return InvoiceStatus.SETTLED;
  }

  private InvoiceStatus mapFailed(WavelengthWire.WalletEntry entry, Instant expiresAt) {
    if (!FAILED_PHASES.contains(entry.progress().phase())) {
      throw protocolFailure();
    }
    var failureCode = entry.failureCode();
    if (failureCode.isPresent()
        && !EXPIRED.equals(failureCode.get())
        && !TERMINAL_FAILURE_CODES.contains(failureCode.get())) {
      throw protocolFailure();
    }
    if (failureCode.filter(EXPIRED::equals).isPresent()) {
      return InvoiceStatus.EXPIRED;
    }
    if (failureCode.isPresent()) {
      return InvoiceStatus.CANCELLED;
    }
    return clock.instant().isBefore(expiresAt) ? InvoiceStatus.CANCELLED : InvoiceStatus.EXPIRED;
  }

  private static WavelengthProtocolException protocolFailure() {
    return new WavelengthProtocolException("Inconsistent Wavelength invoice evidence");
  }
}
