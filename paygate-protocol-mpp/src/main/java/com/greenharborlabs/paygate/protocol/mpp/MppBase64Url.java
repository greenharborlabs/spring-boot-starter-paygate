package com.greenharborlabs.paygate.protocol.mpp;

import com.greenharborlabs.paygate.api.crypto.CryptoUtils;
import java.util.Base64;

/**
 * Canonical base64url (RFC 4648 section 5, without padding) decoding for MPP fields.
 *
 * <p>The JDK decoder also accepts padding and nonzero unused trailing bits, so several strings
 * decode to the same bytes. MPP accepts only the single string that re-encoding produces, so a
 * value such as a challenge {@code id} has exactly one accepted spelling.
 *
 * <p>Package-private. Zero external dependencies.
 */
final class MppBase64Url {

  private MppBase64Url() {} // utility class

  /**
   * Decodes a canonical unpadded base64url value.
   *
   * @param encoded the encoded value
   * @return the decoded bytes
   * @throws IllegalArgumentException if {@code encoded} is null or is not the canonical unpadded
   *     base64url encoding of its bytes
   */
  static byte[] decodeCanonical(String encoded) {
    if (encoded == null || encoded.length() % 4 == 1) {
      throw new IllegalArgumentException("Invalid base64url length");
    }
    for (int index = 0; index < encoded.length(); index++) {
      if (!isAlphabetCharacter(encoded.charAt(index))) {
        throw new IllegalArgumentException("Invalid base64url character");
      }
    }
    byte[] decoded = Base64.getUrlDecoder().decode(encoded);
    // Only the trailing bits of the final character can still differ at this point.
    if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(encoded)) {
      CryptoUtils.zeroize(decoded);
      throw new IllegalArgumentException("Noncanonical base64url encoding");
    }
    return decoded;
  }

  private static boolean isAlphabetCharacter(char character) {
    return (character >= 'A' && character <= 'Z')
        || (character >= 'a' && character <= 'z')
        || (character >= '0' && character <= '9')
        || character == '-'
        || character == '_';
  }
}
