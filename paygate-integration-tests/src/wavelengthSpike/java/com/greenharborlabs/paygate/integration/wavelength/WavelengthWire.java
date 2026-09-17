package com.greenharborlabs.paygate.integration.wavelength;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Pinned Wavelength v0.1.1 REST response shapes used by the Phase 0 spike. */
final class WavelengthWire {

  private static final String INVALID_RESPONSE = "Malformed Wavelength response";
  private static final JsonMapper JSON =
      JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
  private static final Set<String> ENTRY_STATUSES =
      Set.of("ENTRY_STATUS_PENDING", "ENTRY_STATUS_COMPLETE", "ENTRY_STATUS_FAILED");
  private static final Set<String> ENTRY_PHASES =
      Set.of(
          "WALLET_ENTRY_PHASE_REQUEST_CREATED",
          "WALLET_ENTRY_PHASE_WAITING_FOR_PAYMENT",
          "WALLET_ENTRY_PHASE_PAYMENT_DETECTED",
          "WALLET_ENTRY_PHASE_SETTLING",
          "WALLET_ENTRY_PHASE_CONFIRMED",
          "WALLET_ENTRY_PHASE_REFUNDING",
          "WALLET_ENTRY_PHASE_REFUNDED",
          "WALLET_ENTRY_PHASE_FAILED",
          "WALLET_ENTRY_PHASE_WAITING_FOR_CONFIRMATION");
  private static final Set<String> FAILURE_CODES =
      Set.of(
          "ENTRY_FAILURE_CODE_TIMED_OUT",
          "ENTRY_FAILURE_CODE_EXPIRED",
          "ENTRY_FAILURE_CODE_REFUNDED",
          "ENTRY_FAILURE_CODE_NEEDS_INTERVENTION",
          "ENTRY_FAILURE_CODE_FAILED");

  private WavelengthWire() {}

  static JsonMapper jsonMapper() {
    return JSON;
  }

  static StatusResponse parseStatus(byte[] body) {
    var root = object(body);
    var balance = requiredObject(root, "balance");
    return new StatusResponse(
        requiredBoolean(root, "ready"),
        requiredBoolean(root, "unlocked"),
        requiredText(root, "network"),
        new BalanceResponse(
            requiredNonNegativeLongString(balance, "confirmed_sat"),
            requiredNonNegativeLongString(balance, "pending_in_sat"),
            requiredNonNegativeLongString(balance, "pending_out_sat"),
            requiredNonNegativeLongString(balance, "credit_available_sat"),
            requiredNonNegativeLongString(balance, "credit_reserved_sat")),
        requiredNonNegativeInt(root, "pending_count"));
  }

  static RecvResponse parseRecv(byte[] body) {
    var root = object(body);
    var creditNode = root.get("credit_receive");
    CreditReceive credit = null;
    if (creditNode != null && !creditNode.isNull()) {
      if (!creditNode.isObject()) {
        throw protocolFailure();
      }
      credit =
          new CreditReceive(
              requiredText(creditNode, "operation_id"),
              requiredPositiveLongString(creditNode, "amount_sat"),
              requiredText(creditNode, "payment_hash"));
    }
    return new RecvResponse(requiredText(root, "invoice"), requiredEntry(root, "entry"), credit);
  }

  static InspectActivityResponse parseInspectActivity(byte[] body) {
    return new InspectActivityResponse(requiredEntry(object(body), "entry"));
  }

  static ActivityPage parseActivityPage(byte[] body) {
    var activity = requiredObject(object(body), "activity");
    var entriesNode = required(activity, "entries");
    if (!entriesNode.isArray() || entriesNode.size() > WavelengthClient.PAGE_SIZE) {
      throw protocolFailure();
    }
    var entries = new ArrayList<WalletEntry>(entriesNode.size());
    for (var entry : entriesNode) {
      entries.add(parseEntry(entry));
    }
    int total = requiredNonNegativeInt(activity, "total");
    boolean hasMore = requiredBoolean(activity, "has_more");
    String nextCursor = requiredStringAllowEmpty(activity, "next_cursor");
    if (total != entries.size()
        || (hasMore && nextCursor.isBlank())
        || (!hasMore && !nextCursor.isEmpty())) {
      throw protocolFailure();
    }
    return new ActivityPage(List.copyOf(entries), hasMore, nextCursor);
  }

  static Optional<Integer> parseGrpcCode(byte[] body) {
    try {
      var root = object(body);
      var code = required(root, "code");
      if (!code.isIntegralNumber() || !code.canConvertToInt()) {
        return Optional.empty();
      }
      return Optional.of(code.intValue());
    } catch (WavelengthProtocolException ignored) {
      return Optional.empty();
    }
  }

  static byte[] jsonBytes(JsonNode node) {
    try {
      return JSON.writeValueAsString(node).getBytes(StandardCharsets.UTF_8);
    } catch (RuntimeException e) {
      throw new WavelengthProtocolException("Wavelength request could not be encoded", e);
    }
  }

  private static WalletEntry requiredEntry(JsonNode parent, String field) {
    return parseEntry(requiredObject(parent, field));
  }

  private static WalletEntry parseEntry(JsonNode node) {
    if (!node.isObject()) {
      throw protocolFailure();
    }
    var request = requiredObject(node, "request");
    rejectUnexpectedRequestIdentity(request, "onchain_address");
    rejectUnexpectedRequestIdentity(request, "ark_address");
    var lightning = requiredObject(request, "lightning_invoice");
    var progress = requiredObject(node, "progress");

    String failureCode = null;
    var failureNode = node.get("failure_code");
    if (failureNode != null && !failureNode.isNull()) {
      if (!failureNode.isString() || failureNode.asString().isBlank()) {
        throw protocolFailure();
      }
      failureCode = failureNode.asString();
    }

    var entry =
        new WalletEntry(
            requiredText(node, "id"),
            requiredText(node, "kind"),
            requiredText(node, "status"),
            requiredPositiveLongString(node, "amount_sat"),
            requiredPositiveLongString(node, "created_at_unix"),
            requiredPositiveLongString(node, "updated_at_unix"),
            requiredStringAllowEmpty(node, "note"),
            new LightningRequest(
                requiredText(lightning, "invoice"), requiredText(lightning, "payment_hash")),
            new EntryProgress(
                requiredText(progress, "phase"),
                requiredText(progress, "payment_hash"),
                optionalNonEmptyText(progress, "preimage")),
            Optional.ofNullable(failureCode),
            node.has("fee_sat") ? requiredNonNegativeLongString(node, "fee_sat") : null);
    if (!"ENTRY_KIND_RECV".equals(entry.kind())
        || !ENTRY_STATUSES.contains(entry.status())
        || !ENTRY_PHASES.contains(entry.progress().phase())
        || entry.failureCode().filter(code -> !FAILURE_CODES.contains(code)).isPresent()) {
      throw protocolFailure();
    }
    return entry;
  }

  private static void rejectUnexpectedRequestIdentity(JsonNode request, String field) {
    var candidate = request.get(field);
    if (candidate != null && !candidate.isNull()) {
      throw protocolFailure();
    }
  }

  private static JsonNode object(byte[] body) {
    try {
      var root = JSON.readTree(body);
      if (root == null || !root.isObject()) {
        throw protocolFailure();
      }
      return root;
    } catch (WavelengthProtocolException e) {
      throw e;
    } catch (Exception e) {
      throw new WavelengthProtocolException(INVALID_RESPONSE, e);
    }
  }

  private static JsonNode required(JsonNode parent, String field) {
    var value = parent.get(field);
    if (value == null || value.isNull()) {
      throw protocolFailure();
    }
    return value;
  }

  private static JsonNode requiredObject(JsonNode parent, String field) {
    var value = required(parent, field);
    if (!value.isObject()) {
      throw protocolFailure();
    }
    return value;
  }

  private static String requiredText(JsonNode parent, String field) {
    var value = requiredStringAllowEmpty(parent, field);
    if (value.isBlank()) {
      throw protocolFailure();
    }
    return value;
  }

  private static String requiredStringAllowEmpty(JsonNode parent, String field) {
    var value = required(parent, field);
    if (!value.isString()) {
      throw protocolFailure();
    }
    return value.asString();
  }

  private static String optionalNonEmptyText(JsonNode parent, String field) {
    var value = parent.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isString()) {
      throw protocolFailure();
    }
    return value.asString().isEmpty() ? null : requiredText(parent, field);
  }

  private static boolean requiredBoolean(JsonNode parent, String field) {
    var value = required(parent, field);
    if (!value.isBoolean()) {
      throw protocolFailure();
    }
    return value.booleanValue();
  }

  private static int requiredNonNegativeInt(JsonNode parent, String field) {
    var value = required(parent, field);
    if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
      throw protocolFailure();
    }
    return value.intValue();
  }

  private static long requiredPositiveLongString(JsonNode parent, String field) {
    long value = requiredNonNegativeLongString(parent, field);
    if (value == 0) {
      throw protocolFailure();
    }
    return value;
  }

  private static long requiredNonNegativeLongString(JsonNode parent, String field) {
    var text = requiredStringAllowEmpty(parent, field);
    if (!text.matches("0|[1-9][0-9]*")) {
      throw protocolFailure();
    }
    try {
      return Long.parseLong(text);
    } catch (NumberFormatException e) {
      throw new WavelengthProtocolException(INVALID_RESPONSE, e);
    }
  }

  private static WavelengthProtocolException protocolFailure() {
    return new WavelengthProtocolException(INVALID_RESPONSE);
  }

  record StatusResponse(
      boolean ready, boolean unlocked, String network, BalanceResponse balance, int pendingCount) {}

  record BalanceResponse(
      long confirmedSats,
      long pendingInSats,
      long pendingOutSats,
      long creditAvailableSats,
      long creditReservedSats) {}

  record RecvResponse(String invoice, WalletEntry entry, CreditReceive creditReceive) {}

  record CreditReceive(String operationId, long amountSats, String paymentHash) {}

  record InspectActivityResponse(WalletEntry entry) {}

  record ActivityPage(List<WalletEntry> entries, boolean hasMore, String nextCursor) {}

  record WalletEntry(
      String id,
      String kind,
      String status,
      long amountSats,
      long createdAtUnix,
      long updatedAtUnix,
      String note,
      LightningRequest request,
      EntryProgress progress,
      Optional<String> failureCode,
      Long feeSats) {}

  record LightningRequest(String invoice, String paymentHash) {}

  record EntryProgress(String phase, String paymentHash, String preimage) {}
}
