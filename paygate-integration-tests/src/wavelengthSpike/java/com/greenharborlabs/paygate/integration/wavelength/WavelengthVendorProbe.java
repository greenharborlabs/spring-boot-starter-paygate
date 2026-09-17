package com.greenharborlabs.paygate.integration.wavelength;

import com.greenharborlabs.paygate.core.lightning.Invoice;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.LinkedHashMap;

/** Direct Phase 0 receiver probe; browser payment, restart, and Paygate flow remain later gates. */
final class WavelengthVendorProbe {

  private final WavelengthClient client;
  private final WavelengthInvoiceMapper mapper;

  WavelengthVendorProbe(WavelengthClient client, WavelengthInvoiceMapper mapper) {
    this.client = java.util.Objects.requireNonNull(client, "client");
    this.mapper = java.util.Objects.requireNonNull(mapper, "mapper");
  }

  boolean isHealthy() {
    try {
      var status = client.status();
      return status.ready() && "signet".equals(status.network());
    } catch (RuntimeException ignored) {
      return false;
    }
  }

  WavelengthInvoiceMapper.MappedReceive receive(long amountSats, String memo) {
    var status = client.status();
    if (!status.ready() || !"signet".equals(status.network())) {
      throw new WavelengthException("Wavelength wallet is not ready on signet");
    }
    return mapper.mapCreated(client.recv(amountSats, memo), amountSats, memo);
  }

  Invoice lookup(byte[] paymentHash) {
    return lookupObserved(paymentHash).invoice();
  }

  record LookupResult(Invoice invoice, String path) {}

  LookupResult lookupObserved(byte[] paymentHash) {
    if (paymentHash == null || paymentHash.length != 32) {
      throw new IllegalArgumentException("paymentHash must be exactly 32 bytes");
    }
    var budget = client.newLookupBudget();

    // InspectActivity(payment-hash id)
    //   -> exact swap-backed entry
    //   `-> authoritative NOT_FOUND only -> bounded List(receive, cursor...) fallback
    //         -> exact progress.payment_hash match, absence, or explicit exhaustion
    try {
      var inspected = client.inspectActivity(paymentHash, budget).entry();
      if (!inspected.id().equals(HexFormat.of().formatHex(paymentHash))) {
        throw new WavelengthProtocolException("InspectActivity returned a different entry");
      }
      var invoice = mapper.mapLookup(inspected, paymentHash);
      budget.ensureActive();
      return new LookupResult(invoice, "inspect_activity");
    } catch (WavelengthInspectNotFoundException notFound) {
      return new LookupResult(lookupInActivityHistory(paymentHash, budget), "list");
    }
  }

  void writeObservedReceiveEvidence(
      Path runDirectory,
      String runId,
      String manifestSha256,
      WavelengthInvoiceMapper.MappedReceive receive)
      throws IOException {
    var gateDirectory = runDirectory.resolve("receive");
    Files.createDirectory(gateDirectory);
    var evidence = new LinkedHashMap<String, Object>();
    evidence.put("run_id", runId);
    evidence.put("manifest_sha256", manifestSha256);
    evidence.put("gate", "receive");
    evidence.put("operation", "recv");
    evidence.put("outcome", "passed");
    evidence.put("receive_mode", receive.mode().evidenceValue());
    evidence.put("asset_category", receive.mode().assetCategory());
    SanitizedEvidenceWriter.write(gateDirectory, evidence);
  }

  private Invoice lookupInActivityHistory(
      byte[] paymentHash, WavelengthClient.LookupBudget budget) {
    var seenCursors = new java.util.HashSet<String>();
    var cursor = "";
    seenCursors.add(cursor);

    WavelengthWire.WalletEntry match = null;
    while (true) {
      var page = client.list(cursor, budget);
      budget.consumePage(page.entries().size());
      for (var entry : page.entries()) {
        if (mapper.matchesPaymentHash(entry, paymentHash)) {
          if (match != null) {
            throw new WavelengthProtocolException("Wavelength lookup returned duplicate matches");
          }
          match = entry;
        }
      }
      if (!page.hasMore()) {
        if (match == null) {
          throw new WavelengthInvoiceNotFoundException();
        }
        var invoice = mapper.mapLookup(match, paymentHash);
        budget.ensureActive();
        return invoice;
      }
      if (!budget.canContinue()) {
        throw new WavelengthLookupExhaustedException();
      }
      var nextCursor = page.nextCursor();
      if (nextCursor.equals(cursor) || !seenCursors.add(nextCursor)) {
        throw new WavelengthProtocolException("Wavelength lookup cursor did not progress");
      }
      cursor = nextCursor;
    }
  }
}
