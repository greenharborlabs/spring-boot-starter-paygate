package com.greenharborlabs.paygate.integration.wavelength;

import com.greenharborlabs.paygate.api.SecurityBounds;
import com.greenharborlabs.paygate.core.lightning.Invoice;
import com.greenharborlabs.paygate.core.macaroon.MacaroonCrypto;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;

/** Strictly reconstructs the unchanged Paygate Invoice from pinned Wavelength evidence. */
final class WavelengthInvoiceMapper {

  private static final Duration MAX_FUTURE_CLOCK_SKEW = Duration.ofMinutes(5);
  private static final int MAX_MEMO_BYTES = 256;
  private static final String RECEIVE_KIND = "ENTRY_KIND_RECV";
  private static final String PENDING = "ENTRY_STATUS_PENDING";
  private static final String COMPLETE = "ENTRY_STATUS_COMPLETE";

  private final Bolt11Decoder decoder;
  private final Clock clock;
  private final WavelengthEntryStatusMapper statusMapper;
  private final String expectedNetwork;

  WavelengthInvoiceMapper(Bolt11Decoder decoder, Clock clock, String expectedNetwork) {
    this.decoder = java.util.Objects.requireNonNull(decoder, "decoder");
    this.clock = java.util.Objects.requireNonNull(clock, "clock");
    this.statusMapper = new WavelengthEntryStatusMapper(clock);
    if (!"signet".equals(expectedNetwork)) {
      throw new IllegalArgumentException("The Wavelength spike requires signet");
    }
    this.expectedNetwork = expectedNetwork;
  }

  MappedReceive mapCreated(
      WavelengthWire.RecvResponse response, long requestedAmount, String memo) {
    validateRequestedInvoice(requestedAmount, memo);
    if (response == null) {
      throw protocolFailure();
    }
    var entry = response.entry();
    var decoded = validateCommon(entry, response.invoice(), requestedAmount, memo, null);
    if (!PENDING.equals(entry.status()) || !clock.instant().isBefore(decoded.expiresAt())) {
      throw protocolFailure();
    }

    ReceiveMode mode;
    var credit = response.creditReceive();
    var expectedHashHex = HexFormat.of().formatHex(decoded.paymentHash());
    if (credit == null) {
      mode = ReceiveMode.SWAP_BACKED;
      if (!entry.id().equals(expectedHashHex)) {
        throw protocolFailure();
      }
    } else {
      mode = ReceiveMode.CREDIT_BACKED;
      if (!entry.id().equals(credit.operationId())
          || credit.operationId().isBlank()
          || credit.amountSats() != requestedAmount
          || !credit.paymentHash().equals(expectedHashHex)) {
        throw protocolFailure();
      }
    }

    return new MappedReceive(toInvoice(entry, decoded), mode);
  }

  Invoice mapLookup(WavelengthWire.WalletEntry entry, byte[] requestedHash) {
    validateHashWidth(requestedHash);
    if (entry == null) {
      throw protocolFailure();
    }
    var decoded = validateCommon(entry, entry.request().invoice(), null, null, requestedHash);
    return toInvoice(entry, decoded);
  }

  boolean matchesPaymentHash(WavelengthWire.WalletEntry entry, byte[] requestedHash) {
    validateHashWidth(requestedHash);
    if (entry == null || entry.progress() == null) {
      throw protocolFailure();
    }
    return constantTimeEquals(parseHash(entry.progress().paymentHash()), requestedHash);
  }

  private DecodedBolt11 validateCommon(
      WavelengthWire.WalletEntry entry,
      String expectedBolt11,
      Long expectedAmount,
      String expectedMemo,
      byte[] requestedHash) {
    validateEntryMetadata(entry, expectedBolt11, expectedMemo);
    var decoded = decoder.decode(expectedBolt11);
    validateDecodedAmountAndNetwork(entry, decoded, expectedAmount);
    validateTimestamps(entry, decoded);
    validateHashes(entry, decoded, requestedHash);
    statusMapper.validateAndMap(entry, decoded.expiresAt());
    return decoded;
  }

  private static void validateEntryMetadata(
      WavelengthWire.WalletEntry entry, String expectedBolt11, String expectedMemo) {
    if (entry == null || !RECEIVE_KIND.equals(entry.kind())) {
      throw protocolFailure();
    }
    if (entry.updatedAtUnix() < entry.createdAtUnix()
        || entry.note().getBytes(StandardCharsets.UTF_8).length > MAX_MEMO_BYTES) {
      throw protocolFailure();
    }
    if (!entry.request().invoice().equals(expectedBolt11)
        || (expectedMemo != null && !entry.note().equals(expectedMemo))) {
      throw protocolFailure();
    }
  }

  private void validateDecodedAmountAndNetwork(
      WavelengthWire.WalletEntry entry, DecodedBolt11 decoded, Long expectedAmount) {
    if (!networkMatches(decoded.network()) || decoded.paymentHash().length != 32) {
      throw protocolFailure();
    }
    if (!SecurityBounds.isValidPrice(entry.amountSats())
        || decoded.amountSats() != entry.amountSats()
        || (expectedAmount != null && entry.amountSats() != expectedAmount)) {
      throw protocolFailure();
    }
  }

  private void validateTimestamps(WavelengthWire.WalletEntry entry, DecodedBolt11 decoded) {
    var createdAt = safeInstant(entry.createdAtUnix());
    if (!createdAt.equals(decoded.createdAt())
        || createdAt.isAfter(clock.instant().plus(MAX_FUTURE_CLOCK_SKEW))) {
      throw protocolFailure();
    }
    if (!decoded.expiresAt().isAfter(createdAt)) {
      throw protocolFailure();
    }
  }

  private static void validateHashes(
      WavelengthWire.WalletEntry entry, DecodedBolt11 decoded, byte[] requestedHash) {
    var requestHash = parseHash(entry.request().paymentHash());
    var progressHash = parseHash(entry.progress().paymentHash());
    if (!constantTimeEquals(requestHash, progressHash)
        || !constantTimeEquals(requestHash, decoded.paymentHash())) {
      throw protocolFailure();
    }
    if (requestedHash != null && !constantTimeEquals(requestHash, requestedHash)) {
      throw protocolFailure();
    }
  }

  private Invoice toInvoice(WavelengthWire.WalletEntry entry, DecodedBolt11 decoded) {
    byte[] preimage = null;
    byte[] digest = null;
    try {
      if (entry.progress().preimage() != null) {
        if (!COMPLETE.equals(entry.status())) {
          throw protocolFailure();
        }
        preimage = parseHash(entry.progress().preimage());
        digest = sha256(preimage);
        if (!constantTimeEquals(digest, decoded.paymentHash())) {
          throw protocolFailure();
        }
      }
      return new Invoice(
          decoded.paymentHash(),
          entry.request().invoice(),
          entry.amountSats(),
          entry.note(),
          statusMapper.validateAndMap(entry, decoded.expiresAt()),
          preimage,
          decoded.createdAt(),
          decoded.expiresAt());
    } finally {
      if (preimage != null) {
        Arrays.fill(preimage, (byte) 0);
      }
      if (digest != null) {
        Arrays.fill(digest, (byte) 0);
      }
    }
  }

  private boolean networkMatches(String invoiceNetwork) {
    return "signet".equals(expectedNetwork) && "testnet-family".equals(invoiceNetwork);
  }

  private static void validateRequestedInvoice(long amount, String memo) {
    if (!SecurityBounds.isValidPrice(amount)) {
      throw new IllegalArgumentException("amountSats must be within the supported invoice range");
    }
    if (memo == null || memo.getBytes(StandardCharsets.UTF_8).length > MAX_MEMO_BYTES) {
      throw new IllegalArgumentException("memo must be at most 256 UTF-8 bytes");
    }
  }

  private static byte[] parseHash(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw protocolFailure();
    }
    return HexFormat.of().parseHex(value);
  }

  private static void validateHashWidth(byte[] hash) {
    if (hash == null || hash.length != 32) {
      throw new IllegalArgumentException("paymentHash must be exactly 32 bytes");
    }
  }

  private static Instant safeInstant(long epochSeconds) {
    try {
      return Instant.ofEpochSecond(epochSeconds);
    } catch (RuntimeException e) {
      throw new WavelengthProtocolException("Malformed Wavelength timestamp", e);
    }
  }

  private static byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("SHA-256 is unavailable", e);
    }
  }

  private static boolean constantTimeEquals(byte[] left, byte[] right) {
    return MacaroonCrypto.constantTimeEquals(left, right);
  }

  private static WavelengthProtocolException protocolFailure() {
    return new WavelengthProtocolException("Inconsistent Wavelength invoice evidence");
  }

  enum ReceiveMode {
    SWAP_BACKED("swap_backed", "unknown"),
    CREDIT_BACKED("credit_backed", "server_credits");

    private final String evidenceValue;
    private final String assetCategory;

    ReceiveMode(String evidenceValue, String assetCategory) {
      this.evidenceValue = evidenceValue;
      this.assetCategory = assetCategory;
    }

    String evidenceValue() {
      return evidenceValue;
    }

    String assetCategory() {
      return assetCategory;
    }
  }

  record MappedReceive(Invoice invoice, ReceiveMode mode) {}
}
