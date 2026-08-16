package com.greenharborlabs.paygate.core.protocol;

import com.greenharborlabs.paygate.core.lightning.PaymentPreimage;
import com.greenharborlabs.paygate.core.macaroon.KeyMaterial;
import com.greenharborlabs.paygate.core.macaroon.Macaroon;
import com.greenharborlabs.paygate.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.paygate.core.macaroon.MacaroonSerializer;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * An authenticated L402 credential consisting of a macaroon, preimage proof-of-payment, and the
 * hex-encoded token identifier.
 */
public record L402Credential(
    Macaroon macaroon, PaymentPreimage preimage, String tokenId, List<Macaroon> additionalMacaroons)
    implements AutoCloseable {

  public L402Credential {
    Objects.requireNonNull(macaroon, "macaroon must not be null");
    Objects.requireNonNull(preimage, "preimage must not be null");
    Objects.requireNonNull(tokenId, "tokenId must not be null");
    Objects.requireNonNull(additionalMacaroons, "additionalMacaroons must not be null");
    if (tokenId.isEmpty()) {
      throw new IllegalArgumentException("tokenId must not be empty");
    }
    additionalMacaroons = List.copyOf(additionalMacaroons);
  }

  /** Backward-compatible constructor for single-token credentials. */
  public L402Credential(Macaroon macaroon, PaymentPreimage preimage, String tokenId) {
    this(macaroon, preimage, tokenId, List.of());
  }

  private static final HexFormat HEX = HexFormat.of();

  /**
   * Maximum decoded macaroon byte size (base64 can encode up to ~6144 bytes within the 8192-char
   * regex window).
   */
  static final int MAX_MACAROON_BYTES = 4096;

  /**
   * Parses an L402/LSAT Authorization header into an {@link L402Credential}.
   *
   * <p>The returned credential is caller-owned. Callers that retain it beyond request processing
   * are responsible for destroying it when no longer needed.
   *
   * @param authorizationHeader the raw Authorization header value
   * @return a parsed credential
   * @throws L402Exception with {@link ErrorCode#MALFORMED_HEADER} on any parse failure
   */
  public static L402Credential parse(String authorizationHeader) {
    return parse(L402HeaderComponents.extractOrThrow(authorizationHeader));
  }

  /**
   * Parses pre-extracted header components into an {@link L402Credential}.
   *
   * <p>The returned credential is caller-owned. Callers that retain it beyond request processing
   * are responsible for destroying it when no longer needed.
   *
   * @param components the structurally validated header components
   * @return a parsed credential
   * @throws L402Exception with {@link ErrorCode#MALFORMED_HEADER} on any decode failure
   */
  public static L402Credential parse(L402HeaderComponents components) {
    Objects.requireNonNull(components, "components must not be null");

    String tokensString = components.macaroonBase64();
    String preimageHex = components.preimageHex();

    // A credential contains exactly one primary macaroon. Discharge/additional macaroons are not
    // supported, so reject them before decoding or retaining attacker-controlled extra content.
    String[] tokenParts = tokensString.split(",", -1);
    for (String tokenPart : tokenParts) {
      if (tokenPart.isEmpty()) {
        throw new L402Exception(
            ErrorCode.MALFORMED_HEADER, "Empty token in multi-token header", null);
      }
    }
    if (tokenParts.length != 1) {
      throw new L402Exception(
          ErrorCode.MALFORMED_HEADER, "Additional macaroons are unsupported", null);
    }

    // Decode primary (first) macaroon
    Macaroon primaryMacaroon = decodeMacaroon(tokenParts[0]);

    PaymentPreimage preimage;
    try {
      preimage = PaymentPreimage.fromHex(preimageHex);
    } catch (IllegalArgumentException e) {
      throw new L402Exception(
          ErrorCode.MALFORMED_HEADER, "Invalid preimage hex: " + e.getMessage(), null);
    }

    MacaroonIdentifier id;
    try {
      id = MacaroonIdentifier.decode(primaryMacaroon.identifier());
    } catch (IllegalArgumentException e) {
      preimage.destroy();
      throw new L402Exception(ErrorCode.MALFORMED_HEADER, "Malformed L402 credential", null);
    }
    String tokenId = HEX.formatHex(id.tokenId());

    return new L402Credential(primaryMacaroon, preimage, tokenId, List.of());
  }

  /**
   * Returns a caller-owned copy of this credential with the same macaroons and token id, and a
   * distinct {@link PaymentPreimage} containing the same bytes.
   *
   * @throws IllegalStateException if this credential's preimage has already been destroyed
   */
  public L402Credential copy() {
    byte[] value = preimage.value();
    try {
      return new L402Credential(macaroon, new PaymentPreimage(value), tokenId, additionalMacaroons);
    } finally {
      KeyMaterial.zeroize(value);
    }
  }

  /**
   * Destroys only this credential's preimage. Macaroons and token id are immutable metadata and are
   * left unchanged. This method is idempotent.
   */
  public void destroy() {
    preimage.destroy();
  }

  /** Delegates to {@link #destroy()}. */
  @Override
  public void close() {
    destroy();
  }

  private static Macaroon decodeMacaroon(String base64Token) {
    byte[] macaroonBytes;
    try {
      macaroonBytes = Base64.getDecoder().decode(base64Token);
    } catch (IllegalArgumentException e) {
      throw new L402Exception(
          ErrorCode.MALFORMED_HEADER, "Invalid base64 macaroon encoding: " + e.getMessage(), null);
    }

    if (macaroonBytes.length > MAX_MACAROON_BYTES) {
      throw new L402Exception(
          ErrorCode.MALFORMED_HEADER,
          "Macaroon too large: %d bytes, max: %d"
              .formatted(macaroonBytes.length, MAX_MACAROON_BYTES),
          null);
    }

    try {
      return MacaroonSerializer.deserializeV2(macaroonBytes);
    } catch (IllegalArgumentException e) {
      throw new L402Exception(ErrorCode.MALFORMED_HEADER, "Invalid macaroon data", null);
    }
  }

  @Override
  public String toString() {
    return "L402Credential[tokenId=" + tokenId + "]";
  }
}
