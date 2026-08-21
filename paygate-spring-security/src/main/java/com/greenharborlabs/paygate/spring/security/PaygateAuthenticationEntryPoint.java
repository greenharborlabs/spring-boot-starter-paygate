package com.greenharborlabs.paygate.spring.security;

import com.greenharborlabs.paygate.api.ChallengeResponse;
import com.greenharborlabs.paygate.api.PaymentProtocol;
import com.greenharborlabs.paygate.core.macaroon.PathNormalizer;
import com.greenharborlabs.paygate.spring.ApplicationRelativeRequestResolver;
import com.greenharborlabs.paygate.spring.PaygateChallengeService;
import com.greenharborlabs.paygate.spring.PaygateEndpointRegistry;
import com.greenharborlabs.paygate.spring.PaygateLightningUnavailableException;
import com.greenharborlabs.paygate.spring.PaygateRateLimitedException;
import com.greenharborlabs.paygate.spring.PaygateResponseWriter;
import com.greenharborlabs.paygate.spring.RequestBodyTooLargeException;
import com.greenharborlabs.paygate.spring.RequestDigestSupport;
import com.greenharborlabs.paygate.spring.ResolvedEndpoint;
import com.greenharborlabs.paygate.spring.TrustedRequestPrice;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
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
  private static final String AUTHORIZATION_HEADER = "Authorization";
  private final PaygateChallengeService challengeService;
  private final PaygateEndpointRegistry endpointRegistry;
  private final List<PaymentProtocol> protocols;
  private final boolean mppEnabled;

  public PaygateAuthenticationEntryPoint(
      PaygateChallengeService challengeService,
      PaygateEndpointRegistry endpointRegistry,
      List<PaymentProtocol> protocols) {
    this.challengeService =
        Objects.requireNonNull(challengeService, "challengeService must not be null");
    this.endpointRegistry =
        Objects.requireNonNull(endpointRegistry, "endpointRegistry must not be null");
    this.protocols = List.copyOf(Objects.requireNonNull(protocols, "protocols must not be null"));
    this.mppEnabled = this.protocols.stream().anyMatch(RequestDigestSupport::isMppProtocol);
  }

  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authException)
      throws IOException {
    // The entry point is only allowed to mint challenge state for an absent credential. The
    // authentication filter handles recognized payment credentials; a header reaching here is
    // therefore unsupported or malformed and must not trigger invoice or root-key work.
    if (request.getHeader(AUTHORIZATION_HEADER) != null) {
      PaygateResponseWriter.writeMethodUnsupported(response, "Unsupported payment credential");
      return;
    }

    ResolvedEndpoint resolvedEndpoint;
    try {
      resolvedEndpoint = endpointRegistry.resolve(request);
    } catch (IllegalArgumentException e) {
      log.log(System.Logger.Level.WARNING, "Rejected request with malformed URI: <unavailable>");
      PaygateResponseWriter.writeMalformedUri(response);
      return;
    } catch (RuntimeException e) {
      log.log(
          System.Logger.Level.WARNING,
          "Endpoint policy resolution failed: {0}",
          e.getClass().getSimpleName());
      SecurityContextHolder.clearContext();
      PaygateResponseWriter.writeInternalError(response);
      return;
    }
    if (resolvedEndpoint == null) {
      PaygateResponseWriter.writeUnauthorized(response);
      return;
    }

    commence(request, response, resolvedEndpoint);
  }

  /** Resolves the request price once for the Security filter and this entry point. */
  public TrustedRequestPrice resolveTrustedPrice(
      HttpServletRequest request, ResolvedEndpoint resolvedEndpoint) {
    return challengeService.resolveTrustedPrice(request, resolvedEndpoint.config());
  }

  /**
   * Issues an absent-credential challenge using the endpoint already resolved by the authentication
   * filter. This preserves the exact MVC handler mapping used for later enforcement and avoids a
   * second, potentially divergent endpoint lookup.
   *
   * <p>The caller must invoke this operation only when the {@code Authorization} header is truly
   * absent. A presented (including blank) header is never allowed to mint challenge state.
   */
  public void commence(
      HttpServletRequest request, HttpServletResponse response, ResolvedEndpoint resolvedEndpoint)
      throws IOException {
    Objects.requireNonNull(request, "request must not be null");
    Objects.requireNonNull(response, "response must not be null");
    Objects.requireNonNull(resolvedEndpoint, "resolvedEndpoint must not be null");
    if (request.getHeader(AUTHORIZATION_HEADER) != null) {
      PaygateResponseWriter.writeMethodUnsupported(response, "Unsupported payment credential");
      return;
    }
    issueChallenge(request, response, resolvedEndpoint);
  }

  /**
   * Issues a replacement challenge after a typed L402 paid-price failure.
   *
   * <p>The caller may use this only after core validation classified the presented credential as
   * insufficient. It deliberately shares the request-memoized trusted price with the original
   * validation and preserves unavailable failures as 503 at the caller.
   */
  public void commenceReplacement(
      HttpServletRequest request, HttpServletResponse response, ResolvedEndpoint resolvedEndpoint)
      throws IOException {
    Objects.requireNonNull(request, "request must not be null");
    Objects.requireNonNull(response, "response must not be null");
    Objects.requireNonNull(resolvedEndpoint, "resolvedEndpoint must not be null");
    issueChallenge(request, response, resolvedEndpoint);
  }

  private void issueChallenge(
      HttpServletRequest request, HttpServletResponse response, ResolvedEndpoint resolvedEndpoint)
      throws IOException {
    try {
      HttpServletRequest challengeRequest = request;
      challengeService.acquireChallengeRateLimit(request);
      if (mppEnabled) {
        String path = ApplicationRelativeRequestResolver.resolve(request);
        challengeRequest = RequestDigestSupport.wrapForDigest(request);
        RequestDigestSupport.ensureDigestAttribute(challengeRequest, path);
      }

      var challengeContext =
          challengeService.createChallenge(
              challengeRequest,
              resolvedEndpoint,
              PaygateChallengeService.ChallengeOptions.rateLimitAlreadyConsumed());
      try {
        List<ChallengeResponse> challenges = new ArrayList<>();
        for (PaymentProtocol protocol : protocols) {
          try {
            ChallengeResponse challenge = protocol.formatChallenge(challengeContext);
            if (challenge != null) {
              challenges.add(challenge);
            }
          } catch (RuntimeException e) {
            // Do not expose formatter details; another protocol may still issue a usable challenge.
            log.log(
                System.Logger.Level.WARNING,
                "Payment challenge formatter failed; attempting remaining enabled protocols");
          }
        }
        if (challenges.isEmpty()) {
          challengeService.discardChallenge(challengeContext);
          PaygateResponseWriter.writeLightningUnavailable(response);
          return;
        }
        PaygateResponseWriter.writePaymentRequired(response, challengeContext, challenges);
      } finally {
        challengeContext.close();
      }

    } catch (RequestBodyTooLargeException e) {
      log.log(System.Logger.Level.WARNING, "Rejected request: {0}", e.getMessage());
      PaygateResponseWriter.writeRequestBodyTooLarge(response);
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
}
