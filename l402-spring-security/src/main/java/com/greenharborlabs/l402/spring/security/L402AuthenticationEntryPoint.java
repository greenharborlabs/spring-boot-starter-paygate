package com.greenharborlabs.l402.spring.security;

import com.greenharborlabs.l402.core.util.JsonEscaper;
import com.greenharborlabs.l402.spring.L402ChallengeResult;
import com.greenharborlabs.l402.spring.L402ChallengeService;
import com.greenharborlabs.l402.spring.L402EndpointConfig;
import com.greenharborlabs.l402.spring.L402EndpointRegistry;
import com.greenharborlabs.l402.spring.L402LightningUnavailableException;
import com.greenharborlabs.l402.spring.L402RateLimitedException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
                writeServiceUnavailableResponse(response);
                return;
            }

            L402EndpointConfig config = endpointRegistry.findConfig(method, path);
            if (config == null) {
                writeUnauthorizedResponse(response);
                return;
            }

            L402ChallengeResult result = challengeService.createChallenge(request, config);
            writePaymentRequiredResponse(response, result);

        } catch (L402RateLimitedException _) {
            writeRateLimitedResponse(response);
        } catch (L402LightningUnavailableException e) {
            log.log(System.Logger.Level.WARNING, "Lightning unavailable during entry point challenge: {0}", e.getMessage());
            writeServiceUnavailableResponse(response);
        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING, "Unexpected error in L402 entry point: {0}", e.getMessage());
            writeServiceUnavailableResponse(response);
        }
    }

    private void writePaymentRequiredResponse(HttpServletResponse response,
                                               L402ChallengeResult result) throws IOException {
        response.setStatus(HttpServletResponse.SC_PAYMENT_REQUIRED);
        response.setHeader("WWW-Authenticate", result.wwwAuthenticateHeader());
        response.setContentType("application/json");

        String testPreimageField = "";
        if (result.testPreimage() != null) {
            testPreimageField = ", \"test_preimage\": \"" + result.testPreimage() + "\"";
        }

        response.getWriter().write("""
                {"code": 402, "message": "Payment required", "price_sats": %d, "description": "%s", "invoice": "%s"%s}"""
                .formatted(result.priceSats(), JsonEscaper.escape(result.description()), JsonEscaper.escape(result.bolt11()), testPreimageField));
    }

    private void writeRateLimitedResponse(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setHeader("Retry-After", "1");
        response.setContentType("application/json");
        response.getWriter().write("""
                {"code": 429, "error": "RATE_LIMITED", "message": "Too many payment challenge requests. Please try again later."}""");
    }

    private void writeServiceUnavailableResponse(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType("application/json");
        response.getWriter().write("""
                {"code": 503, "error": "LIGHTNING_UNAVAILABLE", "message": "Lightning backend is not available. Please try again later."}""");
    }

    private void writeUnauthorizedResponse(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "L402");
        response.setContentType("application/json");
        response.getWriter().write("""
                {"code": 401, "error": "UNAUTHORIZED", "message": "Authentication required"}""");
    }

    // --- Path normalization (inlined from L402SecurityFilter; will be extracted to shared utility in Wave 3) ---

    /**
     * Normalizes a raw request URI to collapse path traversal sequences
     * ({@code .} and {@code ..}) before endpoint registry lookup.
     */
    static String normalizePath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return "/";
        }

        // Step 1: Iteratively percent-decode until stable (max 3 iterations)
        String decoded = percentDecodePath(rawPath);
        String prev = rawPath;
        int iterations = 0;
        while (!decoded.equals(prev) && iterations < 3) {
            prev = decoded;
            decoded = percentDecodePath(decoded);
            iterations++;
        }

        // Step 2: Collapse . and .. using a segment stack
        String[] segments = decoded.split("/", -1);
        var stack = new java.util.ArrayDeque<String>();
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (!stack.isEmpty()) {
                    stack.removeLast();
                }
            } else {
                stack.addLast(segment);
            }
        }

        // Step 3: Reconstruct
        if (stack.isEmpty()) {
            return "/";
        }
        var sb = new StringBuilder();
        for (String seg : stack) {
            sb.append('/').append(seg);
        }
        return sb.toString();
    }

    /**
     * Percent-decodes a path string without treating {@code +} as space.
     */
    static String percentDecodePath(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        var out = new ByteArrayOutputStream(path.length());
        int i = 0;
        while (i < path.length()) {
            char c = path.charAt(i);
            if (c == '%' && i + 2 < path.length()) {
                int hi = Character.digit(path.charAt(i + 1), 16);
                int lo = Character.digit(path.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) | lo);
                    i += 3;
                } else {
                    out.write(c);
                    i++;
                }
            } else {
                if (c < 0x80) {
                    out.write(c);
                } else {
                    byte[] bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                    out.writeBytes(bytes);
                }
                i++;
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
