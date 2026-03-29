package com.greenharborlabs.paygate.spring.security;

import com.greenharborlabs.paygate.api.ChallengeResponse;
import com.greenharborlabs.paygate.api.PaymentProtocol;
import com.greenharborlabs.paygate.core.macaroon.PathNormalizer;
import com.greenharborlabs.paygate.spring.PaygateChallengeService;
import com.greenharborlabs.paygate.spring.PaygateEndpointConfig;
import com.greenharborlabs.paygate.spring.PaygateEndpointRegistry;
import com.greenharborlabs.paygate.spring.PaygateLightningUnavailableException;
import com.greenharborlabs.paygate.spring.PaygateRateLimitedException;
import com.greenharborlabs.paygate.spring.PaygateResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Spring Security {@link AuthenticationEntryPoint} that issues HTTP 402 Payment Required challenges
 * with Lightning invoices when an unauthenticated request hits a protected endpoint.
 *
 * <p>Follows the pattern of {@code BearerTokenAuthenticationEntryPoint} from Spring OAuth2 Resource
 * Server. Produces response bodies byte-identical to {@code PaygateSecurityFilter}.
 */
public final class PaygateAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private static final System.Logger log =
      System.getLogger(PaygateAuthenticationEntryPoint.class.getName());

  private final PaygateChallengeService challengeService;
  private final PaygateEndpointRegistry endpointRegistry;
  private final List<PaymentProtocol> protocols;

  public PaygateAuthenticationEntryPoint(
      PaygateChallengeService challengeService,
      PaygateEndpointRegistry endpointRegistry,
      List<PaymentProtocol> protocols) {
    this.challengeService =
        Objects.requireNonNull(challengeService, "challengeService must not be null");
    this.endpointRegistry =
        Objects.requireNonNull(endpointRegistry, "endpointRegistry must not be null");
    this.protocols = List.copyOf(Objects.requireNonNull(protocols, "protocols must not be null"));
  }

  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authException)
      throws IOException {
    try {
      String method = request.getMethod();
      String path;
      try {
        path = normalizePath(request.getRequestURI());
      } catch (Exception e) {
        log.log(
            System.Logger.Level.WARNING,
            "Rejected request with malformed URI: {0}",
            sanitizeForLog(request.getRequestURI()));
        PaygateResponseWriter.writeLightningUnavailable(response);
        return;
      }

      PaygateEndpointConfig config = endpointRegistry.findConfig(method, path);
      if (config == null) {
        PaygateResponseWriter.writeUnauthorized(response);
        return;
      }

      var challengeContext = challengeService.createChallenge(request, config);
      List<ChallengeResponse> challenges = new ArrayList<>();
      for (PaymentProtocol protocol : protocols) {
        challenges.add(protocol.formatChallenge(challengeContext));
      }
      PaygateResponseWriter.writePaymentRequired(response, challengeContext, challenges);

    } catch (PaygateRateLimitedException _) {
      PaygateResponseWriter.writeRateLimited(response);
    } catch (PaygateLightningUnavailableException e) {
      // Log exception type only — the message may contain internal backend hostnames/addresses.
      log.log(
          System.Logger.Level.WARNING,
          "Lightning unavailable during entry point challenge: {0}",
          e.getClass().getSimpleName());
      PaygateResponseWriter.writeLightningUnavailable(response);
    } catch (Exception e) {
      // Log exception type only — the message may contain internal backend details.
      log.log(
          System.Logger.Level.WARNING,
          "Unexpected error in payment entry point: {0}",
          e.getClass().getSimpleName());
      PaygateResponseWriter.writeLightningUnavailable(response);
    }
  }

  /** Delegates to {@link PathNormalizer#normalize(String)}. */
  static String normalizePath(String rawPath) {
    return PathNormalizer.normalize(rawPath);
  }

  /** Strips newlines and control characters from user input to prevent log injection. */
  static String sanitizeForLog(String value) {
    if (value == null) {
      return "null";
    }
    return value
        .codePoints()
        .filter(cp -> cp >= 0x20 && cp != 0x7F)
        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
        .toString();
  }
}
