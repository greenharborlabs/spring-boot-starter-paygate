package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
}
