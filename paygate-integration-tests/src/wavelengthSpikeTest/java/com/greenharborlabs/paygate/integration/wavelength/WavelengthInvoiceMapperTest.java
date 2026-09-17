package com.greenharborlabs.paygate.integration.wavelength;

import static com.greenharborlabs.paygate.integration.wavelength.WavelengthTestFixtures.AMOUNT;
import static com.greenharborlabs.paygate.integration.wavelength.WavelengthTestFixtures.CREATED;
import static com.greenharborlabs.paygate.integration.wavelength.WavelengthTestFixtures.MEMO;
import static com.greenharborlabs.paygate.integration.wavelength.WavelengthTestFixtures.NOW;
import static com.greenharborlabs.paygate.integration.wavelength.WavelengthTestFixtures.OTHER_HASH_HEX;
import static com.greenharborlabs.paygate.integration.wavelength.WavelengthTestFixtures.PAYMENT_HASH;
import static com.greenharborlabs.paygate.integration.wavelength.WavelengthTestFixtures.PAYMENT_HASH_HEX;
import static com.greenharborlabs.paygate.integration.wavelength.WavelengthTestFixtures.PREIMAGE;
import static com.greenharborlabs.paygate.integration.wavelength.WavelengthTestFixtures.creditRecvJson;
import static com.greenharborlabs.paygate.integration.wavelength.WavelengthTestFixtures.decoded;
import static com.greenharborlabs.paygate.integration.wavelength.WavelengthTestFixtures.entryJson;
import static com.greenharborlabs.paygate.integration.wavelength.WavelengthTestFixtures.inspectJson;
import static com.greenharborlabs.paygate.integration.wavelength.WavelengthTestFixtures.swapRecvJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.greenharborlabs.paygate.core.lightning.InvoiceStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("Strict Wavelength Invoice mapper")
class WavelengthInvoiceMapperTest {

  @Test
  void feeEvidenceDistinguishesMissingFromExplicitZeroAndRejectsNegative() {
    var root = WavelengthWire.jsonMapper().readTree(swapRecvJson());
    var entry = (tools.jackson.databind.node.ObjectNode) root.get("entry");
    assertThat(WavelengthWire.parseRecv(WavelengthWire.jsonBytes(root)).entry().feeSats()).isNull();
    entry.put("fee_sat", "0");
    assertThat(WavelengthWire.parseRecv(WavelengthWire.jsonBytes(root)).entry().feeSats()).isZero();
    entry.put("fee_sat", "-1");
    assertThatThrownBy(() -> WavelengthWire.parseRecv(WavelengthWire.jsonBytes(root)))
        .isInstanceOf(WavelengthProtocolException.class);
  }

  @Test
  void mapsSwapBackedReceiveWithoutInventingPreimage() {
    var mapped =
        mapper(decoded(), NOW)
            .mapCreated(WavelengthWire.parseRecv(bytes(swapRecvJson())), AMOUNT, MEMO);

    assertThat(mapped.mode()).isEqualTo(WavelengthInvoiceMapper.ReceiveMode.SWAP_BACKED);
    assertThat(mapped.invoice().paymentHash()).isEqualTo(PAYMENT_HASH);
    assertThat(mapped.invoice().amountSats()).isEqualTo(AMOUNT);
    assertThat(mapped.invoice().memo()).isEqualTo(MEMO);
    assertThat(mapped.invoice().status()).isEqualTo(InvoiceStatus.PENDING);
    assertThat(mapped.invoice().preimage()).isNull();
    assertThat(mapped.invoice().createdAt()).isEqualTo(Instant.ofEpochSecond(CREATED));
    assertThat(mapped.invoice().expiresAt()).isEqualTo(Instant.ofEpochSecond(CREATED + 3_600));
  }

  @Test
  void mapsCreditBackedReceiveOnlyWhenOperationHashAndAmountAgree() {
    var mapped =
        mapper(decoded(), NOW)
            .mapCreated(WavelengthWire.parseRecv(bytes(creditRecvJson())), AMOUNT, MEMO);

    assertThat(mapped.mode()).isEqualTo(WavelengthInvoiceMapper.ReceiveMode.CREDIT_BACKED);
    assertThat(mapped.invoice().paymentHash()).isEqualTo(PAYMENT_HASH);
  }

  @ParameterizedTest
  @MethodSource("contradictoryCreateResponses")
  void rejectsContradictoryCreateEvidence(String response) {
    assertThatThrownBy(
            () ->
                mapper(decoded(), NOW)
                    .mapCreated(WavelengthWire.parseRecv(bytes(response)), AMOUNT, MEMO))
        .isInstanceOf(WavelengthProtocolException.class);
  }

  static Stream<Arguments> contradictoryCreateResponses() {
    return Stream.of(
        Arguments.of(
            Named.of(
                "credit amount",
                creditRecvJson().replace("\"amount_sat\":\"10\"", "\"amount_sat\":\"11\""))),
        Arguments.of(
            Named.of(
                "credit operation id",
                creditRecvJson()
                    .replace("credit-op-1\",\"amount_sat", "different-op\",\"amount_sat"))),
        Arguments.of(
            Named.of(
                "credit payment hash",
                replaceLast(creditRecvJson(), PAYMENT_HASH_HEX, OTHER_HASH_HEX))),
        Arguments.of(
            Named.of(
                "swap entry id", swapRecvJson().replaceFirst(PAYMENT_HASH_HEX, OTHER_HASH_HEX))),
        Arguments.of(
            Named.of(
                "persisted memo",
                swapRecvJson().replace("\"note\":\"synthetic memo\"", "\"note\":\"changed\""))),
        Arguments.of(
            Named.of(
                "initial status",
                swapRecvJson()
                    .replace(
                        "\"status\":\"ENTRY_STATUS_PENDING\"",
                        "\"status\":\"ENTRY_STATUS_COMPLETE\"")
                    .replace(
                        "WALLET_ENTRY_PHASE_WAITING_FOR_PAYMENT",
                        "WALLET_ENTRY_PHASE_CONFIRMED"))));
  }

  @Test
  void rejectsMissingReconstructionDataInsteadOfInventingIt() {
    var missingNote = swapRecvJson().replace("\"note\":\"synthetic memo\",", "");
    var missingInvoice = swapRecvJson().replace("\"invoice\":\"synthetic-bolt11\",", "");

    assertThatThrownBy(() -> WavelengthWire.parseRecv(bytes(missingNote)))
        .isInstanceOf(WavelengthProtocolException.class);
    assertThatThrownBy(() -> WavelengthWire.parseRecv(bytes(missingInvoice)))
        .isInstanceOf(WavelengthProtocolException.class);
  }

  @ParameterizedTest
  @MethodSource("statusMappings")
  void mapsStatusesConservatively(
      String status, String failureCode, Instant clockInstant, InvoiceStatus expectedStatus) {
    var preimage = status.equals("ENTRY_STATUS_COMPLETE") ? HexFormat.of().formatHex(PREIMAGE) : "";
    var entry =
        parsedEntry(entryJson(PAYMENT_HASH_HEX, PAYMENT_HASH_HEX, status, failureCode, preimage));

    assertThat(mapper(decoded(), clockInstant).mapLookup(entry, PAYMENT_HASH).status())
        .isEqualTo(expectedStatus);
  }

  static Stream<Arguments> statusMappings() {
    var beforeExpiry = Instant.ofEpochSecond(CREATED + 10);
    var afterExpiry = Instant.ofEpochSecond(CREATED + 3_601);
    return Stream.of(
        Arguments.of("ENTRY_STATUS_PENDING", null, beforeExpiry, InvoiceStatus.PENDING),
        Arguments.of("ENTRY_STATUS_PENDING", null, afterExpiry, InvoiceStatus.EXPIRED),
        Arguments.of("ENTRY_STATUS_COMPLETE", null, beforeExpiry, InvoiceStatus.SETTLED),
        Arguments.of(
            "ENTRY_STATUS_FAILED",
            "ENTRY_FAILURE_CODE_EXPIRED",
            beforeExpiry,
            InvoiceStatus.EXPIRED),
        Arguments.of(
            "ENTRY_STATUS_FAILED",
            "ENTRY_FAILURE_CODE_FAILED",
            afterExpiry,
            InvoiceStatus.CANCELLED),
        Arguments.of("ENTRY_STATUS_FAILED", null, beforeExpiry, InvoiceStatus.CANCELLED),
        Arguments.of("ENTRY_STATUS_FAILED", null, afterExpiry, InvoiceStatus.EXPIRED));
  }

  @ParameterizedTest
  @MethodSource("inconsistentHashes")
  void rejectsEveryHashMismatch(String entry) {
    assertThatThrownBy(() -> mapper(decoded(), NOW).mapLookup(parsedEntry(entry), PAYMENT_HASH))
        .isInstanceOf(WavelengthProtocolException.class);
  }

  static Stream<Arguments> inconsistentHashes() {
    var baseline = entryJson(PAYMENT_HASH_HEX, PAYMENT_HASH_HEX, "ENTRY_STATUS_PENDING", null, "");
    return Stream.of(
        Arguments.of(
            Named.of(
                "request hash",
                baseline.replace(
                    "\"payment_hash\":\"" + PAYMENT_HASH_HEX + "\"}},",
                    "\"payment_hash\":\"" + OTHER_HASH_HEX + "\"}},"))),
        Arguments.of(
            Named.of(
                "progress hash",
                baseline.replace(
                    "\"payment_hash\":\"" + PAYMENT_HASH_HEX + "\",\"preimage\"",
                    "\"payment_hash\":\"" + OTHER_HASH_HEX + "\",\"preimage\""))));
  }

  @Test
  void rejectsMalformedHashEncoding() {
    var malformed = entryJson(PAYMENT_HASH_HEX, "xyz", "ENTRY_STATUS_PENDING", null, "");

    assertThatThrownBy(() -> mapper(decoded(), NOW).mapLookup(parsedEntry(malformed), PAYMENT_HASH))
        .isInstanceOf(WavelengthProtocolException.class);
  }

  @Test
  void rejectsAmountAndDecodedAmountFailures() {
    var entry =
        parsedEntry(
            entryJson(PAYMENT_HASH_HEX, PAYMENT_HASH_HEX, "ENTRY_STATUS_PENDING", null, ""));
    var wrongAmount =
        new DecodedBolt11(
            "testnet-family", PAYMENT_HASH, 11, decoded().createdAt(), decoded().expiresAt());
    var responseWrongAmount =
        swapRecvJson().replace("\"amount_sat\":\"10\"", "\"amount_sat\":\"11\"");

    assertThatThrownBy(() -> mapper(wrongAmount, NOW).mapLookup(entry, PAYMENT_HASH))
        .isInstanceOf(WavelengthProtocolException.class);
    assertThatThrownBy(
            () ->
                mapper(decoded(), NOW)
                    .mapCreated(WavelengthWire.parseRecv(bytes(responseWrongAmount)), AMOUNT, MEMO))
        .isInstanceOf(WavelengthProtocolException.class);
  }

  @ParameterizedTest
  @MethodSource("invalidDecodedInvoices")
  void rejectsNetworkTimestampAndExpiryFailures(DecodedBolt11 invalid) {
    var entry =
        parsedEntry(
            entryJson(PAYMENT_HASH_HEX, PAYMENT_HASH_HEX, "ENTRY_STATUS_PENDING", null, ""));

    assertThatThrownBy(() -> mapper(invalid, NOW).mapLookup(entry, PAYMENT_HASH))
        .isInstanceOf(WavelengthProtocolException.class);
  }

  static Stream<Arguments> invalidDecodedInvoices() {
    return Stream.of(
        Arguments.of(
            Named.of(
                "wrong network",
                new DecodedBolt11(
                    "mainnet",
                    PAYMENT_HASH,
                    AMOUNT,
                    decoded().createdAt(),
                    decoded().expiresAt()))),
        Arguments.of(
            Named.of(
                "timestamp mismatch",
                new DecodedBolt11(
                    "testnet-family",
                    PAYMENT_HASH,
                    AMOUNT,
                    decoded().createdAt().plusSeconds(1),
                    decoded().expiresAt()))),
        Arguments.of(
            Named.of(
                "non-positive expiry",
                new DecodedBolt11(
                    "testnet-family",
                    PAYMENT_HASH,
                    AMOUNT,
                    decoded().createdAt(),
                    decoded().createdAt()))));
  }

  @Test
  void rejectsAlreadyExpiredNewInvoice() {
    assertThatThrownBy(
            () ->
                mapper(decoded(), Instant.ofEpochSecond(CREATED + 3_601))
                    .mapCreated(WavelengthWire.parseRecv(bytes(swapRecvJson())), AMOUNT, MEMO))
        .isInstanceOf(WavelengthProtocolException.class);
  }

  @Test
  void rejectsCreationTimestampBeyondFutureClockSkew() {
    var entry =
        parsedEntry(
            entryJson(PAYMENT_HASH_HEX, PAYMENT_HASH_HEX, "ENTRY_STATUS_PENDING", null, ""));

    assertThatThrownBy(
            () ->
                mapper(decoded(), Instant.ofEpochSecond(CREATED - 301))
                    .mapLookup(entry, PAYMENT_HASH))
        .isInstanceOf(WavelengthProtocolException.class);
  }

  @Test
  void validatesReceiverPreimageAgainstPaymentHash() {
    var valid =
        parsedEntry(
            entryJson(
                PAYMENT_HASH_HEX,
                PAYMENT_HASH_HEX,
                "ENTRY_STATUS_COMPLETE",
                null,
                HexFormat.of().formatHex(PREIMAGE)));
    var invalid =
        parsedEntry(
            entryJson(
                PAYMENT_HASH_HEX,
                PAYMENT_HASH_HEX,
                "ENTRY_STATUS_COMPLETE",
                null,
                "00".repeat(32)));

    assertThat(mapper(decoded(), NOW).mapLookup(valid, PAYMENT_HASH).preimage())
        .isEqualTo(PREIMAGE);
    assertThatThrownBy(() -> mapper(decoded(), NOW).mapLookup(invalid, PAYMENT_HASH))
        .isInstanceOf(WavelengthProtocolException.class);
  }

  @Test
  void rejectsUnknownOrContradictoryStatusFields() {
    var unknown =
        entryJson(PAYMENT_HASH_HEX, PAYMENT_HASH_HEX, "ENTRY_STATUS_PENDING", null, "")
            .replace("ENTRY_STATUS_PENDING", "ENTRY_STATUS_NEW");
    var failedWithPendingPhase =
        entryJson(PAYMENT_HASH_HEX, PAYMENT_HASH_HEX, "ENTRY_STATUS_FAILED", null, "")
            .replace("WALLET_ENTRY_PHASE_FAILED", "WALLET_ENTRY_PHASE_WAITING_FOR_PAYMENT");
    var pendingWithFailure =
        entryJson(
            PAYMENT_HASH_HEX,
            PAYMENT_HASH_HEX,
            "ENTRY_STATUS_PENDING",
            "ENTRY_FAILURE_CODE_FAILED",
            "");

    assertThatThrownBy(() -> parsedEntry(unknown)).isInstanceOf(WavelengthProtocolException.class);
    assertThatThrownBy(
            () ->
                mapper(decoded(), NOW).mapLookup(parsedEntry(failedWithPendingPhase), PAYMENT_HASH))
        .isInstanceOf(WavelengthProtocolException.class);
    assertThatThrownBy(
            () -> mapper(decoded(), NOW).mapLookup(parsedEntry(pendingWithFailure), PAYMENT_HASH))
        .isInstanceOf(WavelengthProtocolException.class);
  }

  @Test
  void rejectsDuplicateJsonIdentityFields() {
    var duplicate =
        swapRecvJson().replaceFirst("\"invoice\":", "\"invoice\":\"shadow\",\"invoice\":");

    assertThatThrownBy(() -> WavelengthWire.parseRecv(bytes(duplicate)))
        .isInstanceOf(WavelengthProtocolException.class);
  }

  private static WavelengthInvoiceMapper mapper(DecodedBolt11 decoded, Instant clockInstant) {
    return new WavelengthInvoiceMapper(
        ignored -> decoded, Clock.fixed(clockInstant, ZoneOffset.UTC), "signet");
  }

  private static WavelengthWire.WalletEntry parsedEntry(String entry) {
    return WavelengthWire.parseInspectActivity(bytes(inspectJson(entry))).entry();
  }

  private static String replaceLast(String value, String target, String replacement) {
    int index = value.lastIndexOf(target);
    return value.substring(0, index) + replacement + value.substring(index + target.length());
  }

  private static byte[] bytes(String value) {
    return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }
}
