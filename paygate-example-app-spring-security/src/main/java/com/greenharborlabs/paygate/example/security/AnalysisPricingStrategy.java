package com.greenharborlabs.paygate.example.security;

import com.greenharborlabs.paygate.spring.PaygatePricingStrategy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component("analysisPricer")
public class AnalysisPricingStrategy implements PaygatePricingStrategy {

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
