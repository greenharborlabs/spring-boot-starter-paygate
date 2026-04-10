package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class RequestDigestSupportTest {

  @Test
  void wrapForDigest_rejectsBodyAboveBound() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/pay");
    request.setContent(new byte[RequestDigestSupport.MAX_CACHED_BODY_BYTES + 1]);

    assertThatThrownBy(() -> RequestDigestSupport.wrapForDigest(request))
        .isInstanceOf(RequestBodyTooLargeException.class)
        .hasMessageContaining("exceeds");
  }

  @Test
  void computeDigest_sameLogicalJsonDifferentWhitespace_producesDifferentDigest() throws Exception {
    MockHttpServletRequest compact = new MockHttpServletRequest("POST", "/api/pay");
    compact.setContent("{\"a\":1,\"b\":2}".getBytes(StandardCharsets.UTF_8));
    MockHttpServletRequest spaced = new MockHttpServletRequest("POST", "/api/pay");
    spaced.setContent("{ \"a\": 1, \"b\": 2 }".getBytes(StandardCharsets.UTF_8));

    String digestCompact = RequestDigestSupport.computeDigest(compact, "/api/pay");
    String digestSpaced = RequestDigestSupport.computeDigest(spaced, "/api/pay");

    assertThat(digestCompact).isNotEqualTo(digestSpaced);
  }

  @Test
  void wrapForDigest_allowsReReadingCachedBody() throws Exception {
    byte[] payload = "{\"k\":\"v\"}".getBytes(StandardCharsets.UTF_8);
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/pay");
    request.setContent(payload);

    var wrapped = RequestDigestSupport.wrapForDigest(request);
    byte[] first = wrapped.getInputStream().readAllBytes();
    byte[] second = wrapped.getInputStream().readAllBytes();

    assertThat(first).isEqualTo(payload);
    assertThat(second).isEqualTo(payload);
  }
}
