package com.greenharborlabs.paygate.example.security;

import java.time.Instant;
import java.util.Map;

import com.greenharborlabs.paygate.spring.PaymentRequired;
import com.greenharborlabs.paygate.spring.security.PaygateAuthenticationToken;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class SecurityExampleController {

    public record HealthResponse(String status) {}
    public record DataResponse(String data, String timestamp) {}
    public record QuoteResponse(String quote, String author, String timestamp) {}
    public record AnalyzeRequest(String content) {}
    public record AnalyzeResponse(String result, int wordCount, String timestamp) {}
    public record ProtocolInfoResponse(String tokenId, String protocol, Map<String, String> attributes, String timestamp) {}

    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public HealthResponse health() {
        return new HealthResponse("ok");
    }

    @PaymentRequired(priceSats = 5, description = "Premium quote of the day")
    @GetMapping(value = "/quote", produces = MediaType.APPLICATION_JSON_VALUE)
    public QuoteResponse quote() {
        return new QuoteResponse(
                "The Times 03/Jan/2009 Chancellor on brink of second bailout for banks.",
                "Satoshi Nakamoto",
                Instant.now().toString()
        );
    }

    @PaymentRequired(priceSats = 10, timeoutSeconds = 3600)
    @GetMapping(value = "/data", produces = MediaType.APPLICATION_JSON_VALUE)
    public DataResponse data() {
        return new DataResponse("premium content", Instant.now().toString());
    }

    @PaymentRequired(priceSats = 50, timeoutSeconds = 3600, pricingStrategy = "analysisPricer")
    @PostMapping(value = "/analyze", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public AnalyzeResponse analyze(@RequestBody AnalyzeRequest request) {
        String content = request.content() != null ? request.content() : "";
        int wordCount = content.isBlank() ? 0 : content.trim().split("\\s+").length;
        return new AnalyzeResponse("Analysis complete", wordCount, Instant.now().toString());
    }

    /**
     * Returns information about the authenticated payment credential.
     * Demonstrates accessing PaygateAuthenticationToken from SecurityContext.
     * Accepts both L402 and MPP protocols.
     */
    @PaymentRequired(priceSats = 1, description = "Protocol info")
    @GetMapping(value = "/protocol-info", produces = MediaType.APPLICATION_JSON_VALUE)
    public ProtocolInfoResponse protocolInfo() {
        var auth = (PaygateAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        return new ProtocolInfoResponse(
                auth.getTokenId(),
                auth.getProtocolScheme(),
                auth.getAttributes(),
                Instant.now().toString()
        );
    }

    /**
     * L402-only endpoint. MPP credentials will be rejected because the
     * SecurityFilterChain requires hasRole("L402") for this path.
     * Demonstrates protocol-specific authorization.
     */
    @PaymentRequired(priceSats = 10, description = "L402-only premium data")
    @GetMapping(value = "/l402-only", produces = MediaType.APPLICATION_JSON_VALUE)
    public DataResponse l402Only() {
        return new DataResponse("L402-exclusive content", Instant.now().toString());
    }

    /**
     * Demonstrates @PreAuthorize with capability-based authorization.
     * Requires both payment AND the "premium-analyze" capability caveat.
     */
    @PaymentRequired(priceSats = 25, capability = "premium-analyze",
            description = "Capability-gated analysis")
    @PreAuthorize("hasAuthority('L402_CAPABILITY_premium-analyze')")
    @PostMapping(value = "/premium-analyze", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public AnalyzeResponse premiumAnalyze(@RequestBody AnalyzeRequest request) {
        String content = request.content() != null ? request.content() : "";
        int wordCount = content.isBlank() ? 0 : content.trim().split("\\s+").length;
        return new AnalyzeResponse("Premium analysis complete", wordCount, Instant.now().toString());
    }
}
