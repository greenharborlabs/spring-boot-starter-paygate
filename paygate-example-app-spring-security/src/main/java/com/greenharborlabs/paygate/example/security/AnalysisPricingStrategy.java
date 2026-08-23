package com.greenharborlabs.paygate.example.security;

import com.greenharborlabs.paygate.spring.BoundedRequestBody;
import com.greenharborlabs.paygate.spring.PaygatePricingStrategy;
import com.greenharborlabs.paygate.spring.PricingEvaluationException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component("analysisPricer")
public class AnalysisPricingStrategy implements PaygatePricingStrategy {

  private static final int BASE_THRESHOLD = 1000;
  private static final int BYTES_PER_SAT = 100;

  @Override
  public long calculatePrice(HttpServletRequest request, long defaultPrice) {
    if (!(request instanceof BoundedRequestBody body)) {
      throw new PricingEvaluationException(
          "Analysis pricing requires bounded observed request bytes");
    }
    int observedLength = body.observedLength();
    if (observedLength <= BASE_THRESHOLD) {
      return defaultPrice;
    }
    return Math.addExact(defaultPrice, observedLength / BYTES_PER_SAT);
  }
}
