package com.greenharborlabs.paygate.api;

import java.util.Map;
import java.util.Optional;

/**
 * Primary extension point for adding payment protocols to the paygate framework.
 *
 * <p>Implementations are registered as Spring beans and injected into the security filter as a
 * {@code List<PaymentProtocol>}. Each implementation handles a single authentication scheme (e.g.,
 * L402, Payment).
 */
public interface PaymentProtocol {

  /**
   * Returns the unique scheme identifier for this protocol.
   *
   * <p>The scheme is used to match incoming {@code Authorization} headers and to format outgoing
   * {@code WWW-Authenticate} challenges (e.g., {@code "L402"}, {@code "Payment"}).
   *
   * @return the scheme identifier, never {@code null}
   */
  String scheme();

  /**
   * Returns whether this protocol can handle the given {@code Authorization} header value.
   *
   * <p>Implementations should perform a fast, prefix-based check and must not throw exceptions for
   * malformed input.
   *
   * @param authorizationHeader the raw {@code Authorization} header value
   * @return {@code true} if this protocol recognizes the header format
   */
  boolean canHandle(String authorizationHeader);

  /**
   * Parses a raw {@code Authorization} header into a protocol-agnostic credential.
   *
   * @param authorizationHeader the raw {@code Authorization} header value
   * @return the parsed credential
   * @throws PaymentValidationException if the header is malformed or cannot be parsed
   */
  PaymentCredential parseCredential(String authorizationHeader) throws PaymentValidationException;

  /**
   * Formats a challenge context into a {@code WWW-Authenticate} header value and accompanying
   * response body data.
   *
   * @param context the challenge context containing invoice and protocol details
   * @return the formatted challenge response
   */
  ChallengeResponse formatChallenge(ChallengeContext context);

  /**
   * Validates a parsed credential, throwing on failure.
   *
   * <p>Implementations should perform all protocol-specific verification (e.g., macaroon signature
   * validation, preimage checks, delegation caveat enforcement).
   *
   * @param credential the parsed credential to validate
   * @param requestContext per-request context (e.g., path, method, client_ip for L402 delegation
   *     caveats). Protocols that do not need request context may ignore this parameter. Must not be
   *     {@code null} — pass an empty map if no context is available.
   * @throws PaymentValidationException if the credential is invalid
   */
  void validate(PaymentCredential credential, Map<String, String> requestContext)
      throws PaymentValidationException;

  /**
   * Creates a receipt after successful validation.
   *
   * <p>The default implementation returns an empty {@link Optional}, indicating that no receipt is
   * produced. Protocols that support receipts (e.g., for proof-of-payment) should override this
   * method.
   *
   * @param credential the validated credential
   * @param context the original challenge context
   * @return an optional receipt, or empty if the protocol does not produce receipts
   */
  default Optional<PaymentReceipt> createReceipt(
      PaymentCredential credential, ChallengeContext context) {
    return Optional.empty();
  }
}
