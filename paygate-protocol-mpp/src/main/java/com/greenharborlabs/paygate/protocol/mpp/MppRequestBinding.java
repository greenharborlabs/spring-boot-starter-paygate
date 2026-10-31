package com.greenharborlabs.paygate.protocol.mpp;

import com.greenharborlabs.paygate.api.ChallengeContext;
import com.greenharborlabs.paygate.api.PaymentValidationException;
import com.greenharborlabs.paygate.api.PaymentValidationException.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Binds an MPP challenge to the paid policy that issued it and verifies that binding.
 *
 * <p>The request digest covers only the HTTP method, application path, raw query, and body. It
 * cannot tell apart two handlers that the web framework selects for the same request line using
 * headers, content negotiation, or request-dependent pricing. This class closes that gap with two
 * checks. Both run only after the challenge HMAC has been verified, so the values they read are
 * server-authenticated:
 *
 * <ul>
 *   <li>The canonical route pattern, request method, and capability declaration are written into
 *       the server {@code opaque} map. That map is already one of the seven HMAC-bound slots. At
 *       validation they must equal the request metadata for the policy now being enforced.
 *   <li>The HMAC-bound charge {@code request.amount}, in satoshis with currency {@code BTC}, must
 *       cover the trusted current price of that policy.
 * </ul>
 *
 * <p>Every missing, malformed, or mismatched value fails closed as {@link ErrorCode#INVALID}.
 */
final class MppRequestBinding {

  /** Opaque key carrying the canonical framework route pattern. */
  static final String OPAQUE_ROUTE_KEY = "paygate_route";

  /** Opaque key carrying the HTTP request method that the challenge was issued for. */
  static final String OPAQUE_METHOD_KEY = "paygate_method";

  /** Opaque key carrying the endpoint capability declaration, or {@code ~} when it has none. */
  static final String OPAQUE_CAPABILITY_KEY = "paygate_capability";

  /** Capability value bound when the endpoint declares no capability (mirrors L402). */
  static final String NO_CAPABILITY = "~";

  // Request metadata keys. These values must match
  // com.greenharborlabs.paygate.core.macaroon.VerificationContextKeys; this module deliberately
  // does not depend on paygate-core.
  static final String REQUEST_ROUTE_KEY = "request.route";
  static final String REQUEST_METHOD_KEY = "request.method";
  static final String REQUESTED_CAPABILITY_KEY = "request.capability";
  static final String CURRENT_PRICE_SATS_KEY = "request.current_price_sats";

  /** Currency emitted by {@link MppProtocol#formatChallenge}; amounts are whole satoshis. */
  static final String CURRENCY = "BTC";

  private static final int MAX_LONG_DIGITS = 19;
  private static final String FAILURE_MESSAGE = "Challenge is not bound to this paid request";

  private MppRequestBinding() {}

  /**
   * Returns the opaque map for a new challenge: the caller-supplied entries plus the policy
   * binding.
   *
   * @throws IllegalArgumentException if the route or method is blank, or if the caller-supplied
   *     opaque already uses a reserved binding key
   */
  static Map<String, String> challengeOpaque(ChallengeContext context) {
    String route = requireBoundary(context.routePattern(), "route pattern");
    String method = requireBoundary(context.requestMethod(), "request method");
    var opaque = new LinkedHashMap<String, String>();
    if (context.opaque() != null) {
      for (String key : context.opaque().keySet()) {
        if (isReservedKey(key)) {
          throw new IllegalArgumentException("opaque key '" + key + "' is reserved");
        }
      }
      opaque.putAll(context.opaque());
    }
    opaque.put(OPAQUE_ROUTE_KEY, route);
    opaque.put(OPAQUE_METHOD_KEY, method);
    opaque.put(OPAQUE_CAPABILITY_KEY, capabilityBinding(context.capability()));
    return opaque;
  }

  /**
   * Verifies the HMAC-authenticated opaque binding and charge amount against the trusted request
   * metadata.
   *
   * <p>Callers must invoke this only after the challenge HMAC has been verified over the same
   * {@code opaqueB64} and {@code requestB64} values.
   *
   * @return the capabilities that the verified binding grants; empty when the policy has none
   * @throws PaymentValidationException with {@link ErrorCode#INVALID} on any missing, malformed, or
   *     mismatched value
   */
  static Set<String> verify(
      String opaqueB64,
      String requestB64,
      Map<String, String> requestContext,
      MppParserLimits limits,
      String tokenId) {
    Map<String, Object> opaque = decodeJsonObject(opaqueB64, limits, tokenId);
    String boundRoute = requireString(opaque.get(OPAQUE_ROUTE_KEY), tokenId);
    String boundMethod = requireString(opaque.get(OPAQUE_METHOD_KEY), tokenId);
    String boundCapability = requireString(opaque.get(OPAQUE_CAPABILITY_KEY), tokenId);

    String requestRoute = requestContext.get(REQUEST_ROUTE_KEY);
    String requestMethod = requestContext.get(REQUEST_METHOD_KEY);
    if (requestRoute == null
        || requestRoute.isBlank()
        || requestMethod == null
        || requestMethod.isBlank()) {
      throw invalid(tokenId);
    }
    String requestCapability = capabilityBinding(requestContext.get(REQUESTED_CAPABILITY_KEY));

    // Evaluate every comparison so the outcome does not reveal which field differed.
    boolean routeMatches = MppCryptoUtils.constantTimeEqualsUtf8(boundRoute, requestRoute);
    boolean methodMatches = MppCryptoUtils.constantTimeEqualsUtf8(boundMethod, requestMethod);
    boolean capabilityMatches =
        MppCryptoUtils.constantTimeEqualsUtf8(boundCapability, requestCapability);
    if (!(routeMatches & methodMatches & capabilityMatches)) {
      throw invalid(tokenId);
    }

    verifyAmount(requestB64, requestContext, limits, tokenId);
    return capabilities(boundCapability, tokenId);
  }

  private static void verifyAmount(
      String requestB64,
      Map<String, String> requestContext,
      MppParserLimits limits,
      String tokenId) {
    Map<String, Object> chargeRequest = decodeJsonObject(requestB64, limits, tokenId);
    String amount = requireString(chargeRequest.get("amount"), tokenId);
    String currency = requireString(chargeRequest.get("currency"), tokenId);
    if (!CURRENCY.equals(currency)) {
      throw invalid(tokenId);
    }
    long paidSats = parseCanonicalNonNegativeLong(amount, tokenId);
    long currentPriceSats =
        parseCanonicalNonNegativeLong(requestContext.get(CURRENT_PRICE_SATS_KEY), tokenId);
    if (paidSats < currentPriceSats) {
      throw invalid(tokenId);
    }
  }

  /**
   * Parses a canonical unsigned decimal: digits only, no sign, fraction, exponent, or whitespace,
   * and no leading zero except for {@code "0"} itself. The value must fit in a {@code long}.
   */
  static long parseCanonicalNonNegativeLong(String value, String tokenId) {
    if (value == null
        || value.isEmpty()
        || value.length() > MAX_LONG_DIGITS
        || (value.length() > 1 && value.charAt(0) == '0')) {
      throw invalid(tokenId);
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c < '0' || c > '9') {
        throw invalid(tokenId);
      }
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException _) {
      throw invalid(tokenId);
    }
  }

  private static Map<String, Object> decodeJsonObject(
      String base64Url, MppParserLimits limits, String tokenId) {
    if (base64Url == null || base64Url.isEmpty()) {
      throw invalid(tokenId);
    }
    byte[] decoded;
    try {
      decoded = MppBase64Url.decodeCanonical(base64Url);
    } catch (IllegalArgumentException _) {
      throw invalid(tokenId);
    }
    try {
      var parser = new MinimalJsonParser(new String(decoded, StandardCharsets.UTF_8), limits);
      Map<String, Object> object = parser.parseObject();
      parser.expectEnd();
      return object;
    } catch (MinimalJsonParser.JsonParseException _) {
      throw invalid(tokenId);
    }
  }

  /** Splits a bound declaration into names using the L402 capability grammar. */
  private static Set<String> capabilities(String boundCapability, String tokenId) {
    if (NO_CAPABILITY.equals(boundCapability)) {
      return Set.of();
    }
    var names = new LinkedHashSet<String>();
    for (String name : boundCapability.split(",", -1)) {
      if (name.isEmpty() || NO_CAPABILITY.equals(name)) {
        throw invalid(tokenId);
      }
      names.add(name);
    }
    return Set.copyOf(names);
  }

  private static String capabilityBinding(String capability) {
    return capability == null || capability.isBlank() ? NO_CAPABILITY : capability;
  }

  private static boolean isReservedKey(String key) {
    return OPAQUE_ROUTE_KEY.equals(key)
        || OPAQUE_METHOD_KEY.equals(key)
        || OPAQUE_CAPABILITY_KEY.equals(key);
  }

  private static String requireBoundary(String value, String boundaryName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("MPP challenge " + boundaryName + " must not be blank");
    }
    return value;
  }

  private static String requireString(Object value, String tokenId) {
    if (value instanceof String string && !string.isEmpty()) {
      return string;
    }
    throw invalid(tokenId);
  }

  private static PaymentValidationException invalid(String tokenId) {
    return new PaymentValidationException(ErrorCode.INVALID, FAILURE_MESSAGE, tokenId);
  }
}
