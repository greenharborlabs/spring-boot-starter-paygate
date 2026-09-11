package com.greenharborlabs.paygate.integration.wavelength;

import fr.acinq.lightning.payment.Bolt11Invoice;
import java.time.Instant;
import java.util.Locale;

interface Bolt11Decoder {
  DecodedBolt11 decode(String bolt11);
}

record DecodedBolt11(
    String network, byte[] paymentHash, long amountSats, Instant createdAt, Instant expiresAt) {
  DecodedBolt11 {
    paymentHash = paymentHash.clone();
  }

  @Override
  public byte[] paymentHash() {
    return paymentHash.clone();
  }
}

final class AcinqBolt11Decoder implements Bolt11Decoder {

  private static final long MSAT_PER_SAT = 1_000L;

  @Override
  public DecodedBolt11 decode(String bolt11) {
    validateEncodedInvoice(bolt11);
    try {
      var parsed = Bolt11Invoice.Companion.read(bolt11);
      if (!parsed.isSuccess()) {
        throw new WavelengthProtocolException("Malformed Wavelength invoice");
      }
      var invoice = parsed.get();
      var createdAt = Instant.ofEpochSecond(invoice.getTimestampSeconds());
      return new DecodedBolt11(
          normalizeNetwork(invoice.getPrefix()),
          paymentHash(invoice),
          amountSats(invoice),
          createdAt,
          createdAt.plusSeconds(expirySeconds(invoice)));
    } catch (WavelengthProtocolException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new WavelengthProtocolException("Malformed Wavelength invoice", e);
    }
  }

  private static void validateEncodedInvoice(String bolt11) {
    if (bolt11 == null || bolt11.isBlank() || bolt11.length() > 4_096 || !isAscii(bolt11)) {
      throw new WavelengthProtocolException("Malformed Wavelength invoice");
    }
  }

  private static long amountSats(Bolt11Invoice invoice) {
    var amount = invoice.getAmount();
    if (amount == null || amount.getMsat() <= 0 || amount.getMsat() % MSAT_PER_SAT != 0) {
      throw new WavelengthProtocolException("Malformed Wavelength invoice amount");
    }
    return Math.floorDiv(amount.getMsat(), MSAT_PER_SAT);
  }

  private static long expirySeconds(Bolt11Invoice invoice) {
    var expiry = invoice.getExpirySeconds();
    long effective = expiry == null ? Bolt11Invoice.DEFAULT_EXPIRY_SECONDS : expiry;
    if (effective <= 0) {
      throw new WavelengthProtocolException("Malformed Wavelength invoice expiry");
    }
    return effective;
  }

  private static byte[] paymentHash(Bolt11Invoice invoice) {
    var paymentHash = invoice.getPaymentHash().toByteArray();
    if (paymentHash.length != 32) {
      throw new WavelengthProtocolException("Malformed Wavelength invoice payment hash");
    }
    return paymentHash;
  }

  private static String normalizeNetwork(String prefix) {
    var lower = prefix.toLowerCase(Locale.ROOT);
    if (lower.startsWith("lnbc")) {
      return "mainnet";
    }
    if (lower.startsWith("lntb")) {
      // BOLT11 alone cannot distinguish the pinned daemon's signet from testnet.
      return "testnet-family";
    }
    if (lower.startsWith("lnbcrt")) {
      return "regtest";
    }
    return "unknown";
  }

  private static boolean isAscii(String value) {
    for (int index = 0; index < value.length(); index++) {
      char candidate = value.charAt(index);
      if (candidate < 0x21 || candidate > 0x7e) {
        return false;
      }
    }
    return true;
  }
}
