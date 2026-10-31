package com.greenharborlabs.paygate.core.lightning;

import java.util.Arrays;

/**
 * Extracts the payment hash from a BOLT #11 Lightning invoice without any external dependency.
 *
 * <p>The invoice is bech32-decoded with checksum verification, then the tagged fields between the
 * 35-bit timestamp and the 104-word signature are walked. The 32-byte value of the single {@code p}
 * field (type 1) with a data length of exactly 52 words is returned. As BOLT #11 requires of
 * readers, a {@code p} field of any other length is skipped.
 *
 * <p>Deliberate restrictions:
 *
 * <ul>
 *   <li>Only lowercase invoices are accepted. BIP-0173 also allows an all-uppercase string, but
 *       Lightning backends return lowercase invoices, so uppercase input is treated as malformed.
 *   <li>The invoice may be at most {@value #MAX_INVOICE_LENGTH} characters, the capacity of a
 *       single QR code and the bound LND applies. BOLT #11 lifts the 90-character bech32 limit.
 *   <li>The human-readable part must start with {@code ln} and contain only lowercase letters and
 *       digits. The currency and amount are not interpreted.
 *   <li>A missing payment hash, more than one 52-word {@code p} field, or a {@code p} field with
 *       non-zero padding bits is a failure.
 *   <li>The node signature is <strong>not</strong> verified, because that needs secp256k1. The
 *       result proves only that the invoice text commits to the hash, not who signed it.
 * </ul>
 *
 * <p>Every failure throws {@link IllegalArgumentException} with a fixed message that never contains
 * the invoice. The decoder makes one pass for the checksum and one for the fields, and allocates
 * only the returned array.
 */
public final class Bolt11PaymentHashExtractor {

  /** Maximum accepted invoice length in characters. */
  public static final int MAX_INVOICE_LENGTH = 7089;

  private static final String CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
  private static final byte[] CHARSET_REV = new byte[128];

  private static final int[] GENERATOR = {
    0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3
  };
  private static final int BECH32_CONST = 1;

  private static final String PREFIX = "ln";
  private static final char SEPARATOR = '1';
  private static final int CHECKSUM_WORDS = 6;
  private static final int TIMESTAMP_WORDS = 7;
  private static final int SIGNATURE_WORDS = 104;
  private static final int TAG_HEADER_WORDS = 3;
  private static final int MIN_DATA_WORDS = TIMESTAMP_WORDS + SIGNATURE_WORDS + CHECKSUM_WORDS;

  private static final int PAYMENT_HASH_TYPE = 1;
  private static final int PAYMENT_HASH_WORDS = 52;
  private static final int PAYMENT_HASH_BYTES = 32;

  static {
    Arrays.fill(CHARSET_REV, (byte) -1);
    for (int i = 0; i < CHARSET.length(); i++) {
      CHARSET_REV[CHARSET.charAt(i)] = (byte) i;
    }
  }

  private Bolt11PaymentHashExtractor() {}

  /**
   * Returns the 32-byte payment hash committed to by {@code bolt11}.
   *
   * @param bolt11 a lowercase BOLT #11 invoice
   * @return a new 32-byte array holding the payment hash
   * @throws IllegalArgumentException if the invoice is null, malformed, fails its checksum, or does
   *     not carry exactly one valid payment hash
   */
  public static byte[] extract(String bolt11) {
    if (bolt11 == null) {
      throw new IllegalArgumentException("BOLT11 invoice must not be null");
    }
    if (bolt11.length() > MAX_INVOICE_LENGTH) {
      throw new IllegalArgumentException("BOLT11 invoice exceeds the maximum length");
    }
    int separator = bolt11.lastIndexOf(SEPARATOR);
    validateHumanReadablePart(bolt11, separator);
    int dataStart = separator + 1;
    if (bolt11.length() - dataStart < MIN_DATA_WORDS) {
      throw new IllegalArgumentException("BOLT11 invoice data part is too short");
    }
    verifyChecksum(bolt11, separator);
    int fieldsEnd = bolt11.length() - CHECKSUM_WORDS - SIGNATURE_WORDS;
    return findPaymentHash(bolt11, dataStart + TIMESTAMP_WORDS, fieldsEnd);
  }

  private static void validateHumanReadablePart(String bolt11, int separator) {
    if (separator <= PREFIX.length() || !bolt11.startsWith(PREFIX)) {
      throw new IllegalArgumentException("BOLT11 invoice has an invalid human-readable part");
    }
    for (int i = PREFIX.length(); i < separator; i++) {
      char c = bolt11.charAt(i);
      if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) {
        throw new IllegalArgumentException("BOLT11 invoice has an invalid human-readable part");
      }
    }
  }

  /** Verifies the bech32 checksum over the expanded human-readable part and the data words. */
  private static void verifyChecksum(String bolt11, int separator) {
    int checksum = 1;
    for (int i = 0; i < separator; i++) {
      checksum = polymodStep(checksum, bolt11.charAt(i) >>> 5);
    }
    checksum = polymodStep(checksum, 0);
    for (int i = 0; i < separator; i++) {
      checksum = polymodStep(checksum, bolt11.charAt(i) & 0x1f);
    }
    for (int i = separator + 1; i < bolt11.length(); i++) {
      checksum = polymodStep(checksum, word(bolt11, i));
    }
    if (checksum != BECH32_CONST) {
      throw new IllegalArgumentException("BOLT11 invoice checksum is invalid");
    }
  }

  private static int polymodStep(int checksum, int value) {
    int top = checksum >>> 25;
    int next = ((checksum & 0x1ffffff) << 5) ^ value;
    for (int i = 0; i < GENERATOR.length; i++) {
      if (((top >>> i) & 1) != 0) {
        next ^= GENERATOR[i];
      }
    }
    return next;
  }

  /** Walks the tagged fields in {@code [start, end)} and returns the single valid payment hash. */
  private static byte[] findPaymentHash(String bolt11, int start, int end) {
    byte[] paymentHash = null;
    int position = start;
    while (position < end) {
      if (end - position < TAG_HEADER_WORDS) {
        throw new IllegalArgumentException("BOLT11 invoice has a truncated tagged field");
      }
      int type = word(bolt11, position);
      int length = (word(bolt11, position + 1) << 5) | word(bolt11, position + 2);
      position += TAG_HEADER_WORDS;
      if (length > end - position) {
        throw new IllegalArgumentException("BOLT11 invoice has a truncated tagged field");
      }
      if (type == PAYMENT_HASH_TYPE && length == PAYMENT_HASH_WORDS) {
        if (paymentHash != null) {
          throw new IllegalArgumentException("BOLT11 invoice has more than one payment hash");
        }
        paymentHash = decodePaymentHash(bolt11, position);
      }
      position += length;
    }
    if (paymentHash == null) {
      throw new IllegalArgumentException("BOLT11 invoice has no payment hash");
    }
    return paymentHash;
  }

  /** Converts 52 five-bit words into 32 bytes, requiring the 4 trailing padding bits to be zero. */
  private static byte[] decodePaymentHash(String bolt11, int start) {
    byte[] out = new byte[PAYMENT_HASH_BYTES];
    int accumulator = 0;
    int bits = 0;
    int index = 0;
    for (int i = start; i < start + PAYMENT_HASH_WORDS; i++) {
      accumulator = ((accumulator << 5) | word(bolt11, i)) & 0xfff;
      bits += 5;
      if (bits >= 8) {
        bits -= 8;
        out[index++] = (byte) (accumulator >>> bits);
      }
    }
    if ((accumulator & ((1 << bits) - 1)) != 0) {
      throw new IllegalArgumentException("BOLT11 invoice payment hash has non-zero padding");
    }
    return out;
  }

  private static int word(String bolt11, int index) {
    char c = bolt11.charAt(index);
    int value = c < CHARSET_REV.length ? CHARSET_REV[c] : -1;
    if (value < 0) {
      throw new IllegalArgumentException("BOLT11 invoice contains an invalid character");
    }
    return value;
  }
}
