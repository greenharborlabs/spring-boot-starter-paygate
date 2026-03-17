package com.greenharborlabs.l402.spring.security;

import com.greenharborlabs.l402.spring.L402ChallengeResult;
import com.greenharborlabs.l402.spring.L402ChallengeService;
import com.greenharborlabs.l402.spring.L402EndpointConfig;
import com.greenharborlabs.l402.spring.L402EndpointRegistry;
import com.greenharborlabs.l402.spring.L402LightningUnavailableException;
import com.greenharborlabs.l402.spring.L402PathUtils;
import com.greenharborlabs.l402.spring.L402RateLimitedException;
import com.greenharborlabs.l402.spring.L402ResponseWriter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;
import java.util.Objects;

/**
 * Spring Security {@link AuthenticationEntryPoint} that issues HTTP 402 Payment Required
 * challenges with Lightning invoices when an unauthenticated request hits a protected endpoint.
 *
 * <p>Follows the pattern of {@code BearerTokenAuthenticationEntryPoint} from Spring OAuth2
 * Resource Server. Produces response bodies byte-identical to {@code L402SecurityFilter}.
 */
public final class L402AuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final System.Logger log = System.getLogger(L402AuthenticationEntryPoint.class.getName());

    private final L402ChallengeService challengeService;
    private final L402EndpointRegistry endpointRegistry;

    public L402AuthenticationEntryPoint(L402ChallengeService challengeService,
                                         L402EndpointRegistry endpointRegistry) {
        this.challengeService = Objects.requireNonNull(challengeService, "challengeService must not be null");
        this.endpointRegistry = Objects.requireNonNull(endpointRegistry, "endpointRegistry must not be null");
    }

    @Override
    public void commence(HttpServletRequest request,
                          HttpServletResponse response,
                          AuthenticationException authException) throws IOException {
        try {
            String method = request.getMethod();
            String path;
            try {
                path = normalizePath(request.getRequestURI());
            } catch (Exception e) {
                log.log(System.Logger.Level.WARNING, "Rejected request with malformed URI: {0}", request.getRequestURI());
                L402ResponseWriter.writeLightningUnavailable(response);
                return;
            }

            L402EndpointConfig config = endpointRegistry.findConfig(method, path);
            if (config == null) {
                L402ResponseWriter.writeUnauthorized(response);
                return;
            }

            L402ChallengeResult result = challengeService.createChallenge(request, config);
            L402ResponseWriter.writePaymentRequired(response, result);

        } catch (L402RateLimitedException _) {
            L402ResponseWriter.writeRateLimited(response);
        } catch (L402LightningUnavailableException e) {
            log.log(System.Logger.Level.WARNING, "Lightning unavailable during entry point challenge: {0}", e.getMessage());
            L402ResponseWriter.writeLightningUnavailable(response);
        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING, "Unexpected error in L402 entry point: {0}", e.getMessage());
            L402ResponseWriter.writeLightningUnavailable(response);
        }
    }

    /**
     * Delegates to {@link L402PathUtils#normalizePath(String)}.
     */
    static String normalizePath(String rawPath) {
        return L402PathUtils.normalizePath(rawPath);
    }
}
