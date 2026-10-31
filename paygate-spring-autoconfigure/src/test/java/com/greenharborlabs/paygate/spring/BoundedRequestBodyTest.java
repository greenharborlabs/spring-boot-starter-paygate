package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;

class BoundedRequestBodyTest {

  @Test
  void capturesAndReplaysTheBoundedObservedBody() throws Exception {
    byte[] body = "trusted bytes".getBytes(StandardCharsets.UTF_8);
    var request = new MockHttpServletRequest("POST", "/api/analyze");
    request.setContent(body);

    var captured = BoundedRequestBody.capture(request, body.length);

    assertThat(captured.observedLength()).isEqualTo(body.length);
    assertThat(captured.getInputStream().readAllBytes()).isEqualTo(body);
    assertThat(captured.getInputStream().readAllBytes()).isEqualTo(body);
  }

  @Test
  void rejectsOverflowBeforeItCanBeReplayed() {
    var request = new MockHttpServletRequest("POST", "/api/analyze");
    request.setContent(new byte[11]);

    assertThatThrownBy(() -> BoundedRequestBody.capture(request, 10))
        .isInstanceOf(RequestBodyTooLargeException.class);
  }

  @Test
  void propagatesDisconnectWhileObservingTheOriginalStream() throws Exception {
    HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
    Mockito.when(request.getInputStream())
        .thenReturn(
            new ServletInputStream() {
              @Override
              public int read() throws IOException {
                throw new IOException("client disconnected");
              }

              @Override
              public boolean isFinished() {
                return false;
              }

              @Override
              public boolean isReady() {
                return true;
              }

              @Override
              public void setReadListener(jakarta.servlet.ReadListener readListener) {}
            });

    assertThatThrownBy(() -> BoundedRequestBody.capture(request, 10))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("disconnected");
  }

  @Test
  void acceptsOnlyAbsentOrIdentityContentEncoding() throws Exception {
    var absent = new MockHttpServletRequest("POST", "/api/analyze");
    var identity = new MockHttpServletRequest("POST", "/api/analyze");
    identity.addHeader("Content-Encoding", "identity");
    var compressed = new MockHttpServletRequest("POST", "/api/analyze");
    compressed.addHeader("Content-Encoding", "gzip");

    assertThat(BoundedRequestBody.capture(absent, 10).hasIdentityContentEncoding()).isTrue();
    assertThat(BoundedRequestBody.capture(identity, 10).hasIdentityContentEncoding()).isTrue();
    assertThat(BoundedRequestBody.capture(compressed, 10).hasIdentityContentEncoding()).isFalse();
  }

  @Test
  void rejectsBodyShorterThanDeclaredContentLength() {
    var request = new DeclaredLengthRequest("PUT", "/api/form", 17);
    request.setContent("units=1".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> BoundedRequestBody.capture(request, 64))
        .isInstanceOf(RequestBodyIncompleteException.class);
  }

  @Test
  void acceptsChunkedBodyWithoutDeclaredLength() throws Exception {
    byte[] body = "units=1".getBytes(StandardCharsets.UTF_8);
    var request = new DeclaredLengthRequest("PUT", "/api/form", -1);
    request.setContent(body);

    assertThat(BoundedRequestBody.capture(request, 64).observedBytes()).isEqualTo(body);
  }

  @Test
  void laterCaptureOfTheSameRequestReusesTheObservedBytes() throws Exception {
    byte[] body = "units=1".getBytes(StandardCharsets.UTF_8);
    var request = new MockHttpServletRequest("PUT", "/api/form");
    request.setContent(body);
    BoundedRequestBody.capture(request, 64);
    var laterView = new HttpServletRequestWrapper(request);

    var recaptured = BoundedRequestBody.capture(laterView, 64);

    assertThat(recaptured.observedBytes()).isEqualTo(body);
    assertThat(recaptured.getInputStream().readAllBytes()).isEqualTo(body);
    assertThat(recaptured.getRequest()).isSameAs(laterView);
  }

  @Test
  void reuseStillEnforcesTheCallersBound() throws Exception {
    var request = new MockHttpServletRequest("PUT", "/api/form");
    request.setContent(new byte[32]);
    BoundedRequestBody.capture(request, 64);

    assertThatThrownBy(() -> BoundedRequestBody.capture(request, 16))
        .isInstanceOf(RequestBodyTooLargeException.class);
  }

  @Test
  void exposesFormPostParametersAfterQueryParameters() throws Exception {
    var request = new MockHttpServletRequest("POST", "/api/form");
    request.setContentType("application/x-www-form-urlencoded; charset=UTF-8");
    request.setCharacterEncoding("UTF-8");
    request.addParameter("units", "0");
    request.setContent(
        "units=1&name=caf%C3%A9&flag&=nameless&bad=%ZZ&plus=a+b&&tail=%41"
            .getBytes(StandardCharsets.UTF_8));

    var captured = BoundedRequestBody.capture(request, 256);

    assertThat(captured.getParameterValues("units")).containsExactly("0", "1");
    assertThat(captured.getParameter("units")).isEqualTo("0");
    assertThat(captured.getParameter("name")).isEqualTo("café");
    assertThat(captured.getParameter("flag")).isEmpty();
    assertThat(captured.getParameter("plus")).isEqualTo("a b");
    assertThat(captured.getParameter("tail")).isEqualTo("A");
    assertThat(captured.getParameter("bad")).isNull();
    assertThat(captured.getParameterMap()).doesNotContainKey("");
    assertThat(Collections.list(captured.getParameterNames()))
        .containsExactly("units", "name", "flag", "plus", "tail");
  }

  @Test
  void leavesNonPostAndNonFormParametersToTheContainer() throws Exception {
    byte[] body = "units=1".getBytes(StandardCharsets.UTF_8);
    var put = new MockHttpServletRequest("PUT", "/api/form");
    put.setContentType("application/x-www-form-urlencoded");
    put.setContent(body);
    var json = new MockHttpServletRequest("POST", "/api/form");
    json.setContentType("application/json");
    json.setContent(body);

    assertThat(BoundedRequestBody.capture(put, 64).getParameter("units")).isNull();
    assertThat(BoundedRequestBody.capture(json, 64).getParameter("units")).isNull();
  }

  @Test
  void nestedCaptureDoesNotDuplicateFormParameters() throws Exception {
    var request = new MockHttpServletRequest("POST", "/api/form");
    request.setContentType("application/x-www-form-urlencoded");
    request.setContent("units=1".getBytes(StandardCharsets.UTF_8));
    var early = BoundedRequestBody.capture(request, 64);

    var later = BoundedRequestBody.capture(new HttpServletRequestWrapper(early), 64);

    assertThat(later.getParameterValues("units")).containsExactly("1");
  }

  /** Mock request whose declared Content-Length differs from the content actually available. */
  private static final class DeclaredLengthRequest extends MockHttpServletRequest {
    private final long declaredLength;

    DeclaredLengthRequest(String method, String uri, long declaredLength) {
      super(method, uri);
      this.declaredLength = declaredLength;
    }

    @Override
    public long getContentLengthLong() {
      return declaredLength;
    }
  }
}
