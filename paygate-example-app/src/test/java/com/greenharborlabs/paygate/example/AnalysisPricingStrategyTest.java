package com.greenharborlabs.paygate.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.greenharborlabs.paygate.spring.BoundedRequestBody;
import com.greenharborlabs.paygate.spring.PricingEvaluationException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class AnalysisPricingStrategyTest {

  private final AnalysisPricingStrategy strategy = new AnalysisPricingStrategy();

  @Test
  void pricesObservedBytesRatherThanDeclaredLength() throws Exception {
    for (String declaredLength : new String[] {"0", "1", "999999", "-1"}) {
      var request = new MockHttpServletRequest("POST", "/api/v1/analyze");
      request.addHeader("Content-Length", declaredLength);
      request.setContent(new byte[1_200]);

      assertThat(strategy.calculatePrice(BoundedRequestBody.capture(request, 8_192), 50))
          .isEqualTo(62);
    }
  }

  @Test
  void keepsEmptyAndThresholdBodiesAtTheBasePrice() throws Exception {
    assertThat(priceFor(0)).isEqualTo(50);
    assertThat(priceFor(1_000)).isEqualTo(50);
    assertThat(priceFor(1_001)).isEqualTo(60);
  }

  @Test
  void rejectsAnUnobservedRequest() {
    assertThatThrownBy(
            () ->
                strategy.calculatePrice(new MockHttpServletRequest("POST", "/api/v1/analyze"), 50))
        .isInstanceOf(PricingEvaluationException.class);
  }

  private long priceFor(int bytes) throws Exception {
    var request = new MockHttpServletRequest("POST", "/api/v1/analyze");
    request.setContent(new byte[bytes]);
    return strategy.calculatePrice(BoundedRequestBody.capture(request, 8_192), 50);
  }
}
