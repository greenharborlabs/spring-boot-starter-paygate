package com.greenharborlabs.l402.example;

import com.greenharborlabs.l402.spring.L402PricingStrategy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Dynamic pricing strategy that scales the invoice price based on request
 * content length. Requests up to 1 000 bytes pay the default price; larger
 * payloads add 1 sat per 100 bytes of content.
 */
@Component("analysisPricer")
public class AnalysisPricingStrategy implements L402PricingStrategy {

    private static final int BASE_THRESHOLD = 1000;
    private static final int BYTES_PER_SAT = 100;

    @Override
    public long calculatePrice(HttpServletRequest request, long defaultPrice) {
        int contentLength = request.getContentLength();
        if (contentLength <= BASE_THRESHOLD) {
            return defaultPrice;
        }
        return defaultPrice + contentLength / BYTES_PER_SAT;
    }
}
