package com.greenharborlabs.paygate.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.web.servlet.function.RouterFunctions.route;

import com.greenharborlabs.paygate.core.lightning.Invoice;
import com.greenharborlabs.paygate.core.lightning.InvoiceStatus;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.spring.PaygateEndpointConfig;
import com.greenharborlabs.paygate.spring.PaygateEndpointRegistry;
import com.greenharborlabs.paygate.spring.PaymentRequired;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Regression for SR-2026-10-18-16: {@code @PaymentRequired} in an additional {@code
 * DispatcherServlet}'s child context was never scanned, so the child served paid routes for free.
 * The root context's guard now fails the child's refresh:
 *
 * <ul>
 *   <li>A load-on-startup child dispatcher with paid handlers fails application startup.
 *   <li>A lazily initialised one fails on every dispatch, so its paid route is never served unpaid.
 *   <li>Child dispatchers without paid handlers, and root-only paid routes, keep working.
 * </ul>
 *
 * <p>Real embedded Tomcat and raw socket probes. A 200 response whose body starts with {@code
 * PAID-} means protected handler work ran without payment.
 */
@Tag("integration")
@SpringBootTest(
    classes = ChildDispatcherServletRegressionTest.LazyChildApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "paygate.enabled=true",
      "paygate.test-mode=false",
      "paygate.root-key-store=memory",
      "paygate.service-name=child-dispatcher",
      "paygate.protocols.mpp.enabled=false",
      "paygate.health-cache.enabled=false",
      "paygate.rate-limit.burst-size=100000",
      "paygate.rate-limit.requests-per-second=100000",
      "paygate.rate-limit.aggregate.burst-size=100000",
      "paygate.rate-limit.aggregate.requests-per-second=100000",
      "spring.main.allow-bean-definition-overriding=true",
      "spring.mvc.hiddenmethod.filter.enabled=true"
    })
@DisplayName("@PaymentRequired in a child DispatcherServlet context (SR-16)")
class ChildDispatcherServletRegressionTest {

  private static final String[] STANDALONE_PROPERTIES = {
    "server.port=0",
    "paygate.enabled=true",
    "paygate.test-mode=false",
    "paygate.root-key-store=memory",
    "paygate.service-name=child-dispatcher",
    "paygate.protocols.mpp.enabled=false",
    "paygate.health-cache.enabled=false"
  };

  @LocalServerPort int port;

  @Test
  @DisplayName("a lazily initialised child dispatcher never serves its paid route unpaid")
  void lazyChildDispatcherPaidRouteIsNeverServedUnpaid() throws Exception {
    for (String path : new String[] {"/second/paid", "/third/paid"}) {
      for (int attempt = 0; attempt < 2; attempt++) {
        var result = raw("GET", path);

        assertThat(result.status()).as("%s attempt %d", path, attempt).isEqualTo(500);
        assertThat(result.bodyHead()).as("%s attempt %d", path, attempt).doesNotContain("PAID-");
      }
    }
  }

  @Test
  @DisplayName("root-context paid and free routes keep working beside a failed child dispatcher")
  void rootRoutesKeepWorking() throws Exception {
    assertThat(raw("GET", "/mvc/paid").status()).isEqualTo(402);
    assertThat(raw("GET", "/fn/abc").status()).isEqualTo(402);
    var free = raw("GET", "/mvc/free");
    assertThat(free.status()).isEqualTo(200);
    assertThat(free.bodyHead()).isEqualTo("FREE");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "/mvc/paid",
        "/iface/paid",
        "/fn/abc",
        "/fnall/x",
        "/mvc/paid/",
        "/mvc//paid",
        "//mvc/paid",
        "/mvc/paid;x=1",
        "/mvc;x=1/paid",
        "/mvc/./paid",
        "/mvc/x/../paid",
        "/mvc/%2e/paid",
        "/mvc/x/%2e%2e/paid",
        "/mvc/%70aid",
        "/mvc/%2570aid",
        "/MVC/paid",
        "/mvc/paid.json",
        "/mvc/item/..",
        "/mvc/item/%2e%2e",
        "/mvc/item/%252e%252e",
        "/mvc/item/a%2Fb",
        "/mvc/view-forward",
        "/mvc/dispatch-forward",
        "/mvc/include",
        "/fn/..",
        "/fn/%2e%2e",
        "/fn/%252e%252e",
        "/fn/.%252e",
        "/fn/%252e",
        "/fn/abc;x=1",
        "/fn/abc/",
        "/fn//abc",
        "/fnall/%252e%252e",
        "/fnall/a/%252e%252e/%252e%252e",
        "/fnall/..",
        "/fnall/x/",
        "/fnall//x",
        "/second/paid",
        "/third/paid"
      })
  @DisplayName("no GET probe serves paid content unpaid")
  void noGetProbeServesPaidContentUnpaid(String rawPath) throws Exception {
    assertThat(raw("GET", rawPath).bypass()).isFalse();
  }

  @Test
  @DisplayName("no HEAD, DELETE or method-override probe serves paid content unpaid")
  void noOtherMethodProbeServesPaidContentUnpaid() throws Exception {
    assertThat(raw("HEAD", "/mvc/paid").status()).isNotEqualTo(200);
    assertThat(raw("HEAD", "/fn/%252e%252e").status()).isNotEqualTo(200);
    assertThat(raw("DELETE", "/fndel").bypass()).isFalse();
    assertThat(raw("DELETE", "/mvc/res").bypass()).isFalse();
    assertThat(rawForm("/fndel", "_method=DELETE").bypass()).isFalse();
    assertThat(rawForm("/mvc/res", "_method=DELETE").bypass()).isFalse();
  }

  @Test
  @DisplayName("a load-on-startup child dispatcher with paid handlers fails application startup")
  void eagerChildDispatcherWithPaidHandlersFailsStartup() {
    var builder =
        new SpringApplicationBuilder(EagerPaidChildApp.class).properties(STANDALONE_PROPERTIES);

    assertThatThrownBy(builder::run)
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("@PaymentRequired is not supported in child application context")
        .hasMessageContaining("/eagerDispatcher");
  }

  @Test
  @DisplayName("a load-on-startup child dispatcher without paid handlers still starts")
  void eagerChildDispatcherWithoutPaidHandlersStarts() throws Exception {
    try (var context =
        new SpringApplicationBuilder(EagerFreeChildApp.class)
            .properties(STANDALONE_PROPERTIES)
            .run()) {
      int localPort =
          Integer.parseInt(context.getEnvironment().getRequiredProperty("local.server.port"));

      var free = raw(localPort, "GET", "/free-child/free");
      assertThat(free.status()).isEqualTo(200);
      assertThat(free.bodyHead()).isEqualTo("FREE-CHILD");
      assertThat(raw(localPort, "GET", "/mvc/paid").status()).isEqualTo(402);
    }
  }

  /** Root application with lazily initialised child dispatchers that declare paid handlers. */
  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import({MvcPaid.class, ForwardingController.class, InterfacePaidImpl.class})
  static class LazyChildApp {

    @Bean
    LightningBackend stubLightningBackend() {
      return new StubBackend();
    }

    /** Functional endpoint: the paid resource is protected only through manual registration. */
    @Bean
    RouterFunction<ServerResponse> paidFunctionalRoutes() {
      return route()
          .GET("/fn/{id}", r -> ServerResponse.ok().body("PAID-FN id=" + r.pathVariable("id")))
          .GET("/fnall/**", r -> ServerResponse.ok().body("PAID-FNALL path=" + r.path()))
          .DELETE("/fndel", r -> ServerResponse.ok().body("PAID-FNDEL"))
          .build();
    }

    /** Documented public API: manual registration of the functional routes. */
    @Bean
    SmartInitializingSingleton registerManualPaidRoutes(PaygateEndpointRegistry registry) {
      return () -> {
        registry.register(new PaygateEndpointConfig("GET", "/fn/{id}", 10, 600, "fn", "", ""));
        registry.register(new PaygateEndpointConfig("GET", "/fnall/**", 10, 600, "fnall", "", ""));
        registry.register(new PaygateEndpointConfig("DELETE", "/fndel", 10, 600, "fndel", "", ""));
      };
    }

    /** Child dispatcher with {@code @EnableWebMvc}. */
    @Bean
    ServletRegistrationBean<DispatcherServlet> secondDispatcher() {
      return childDispatcher("secondDispatcher", "/second/*", -1, ChildWebMvcConfig.class);
    }

    /** Child dispatcher with default MVC strategies, which registers no HandlerMapping beans. */
    @Bean
    ServletRegistrationBean<DispatcherServlet> thirdDispatcher() {
      return childDispatcher("thirdDispatcher", "/third/*", -1);
    }
  }

  /** Root application with a load-on-startup child dispatcher that declares paid handlers. */
  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import(MvcPaid.class)
  static class EagerPaidChildApp {

    @Bean
    LightningBackend stubLightningBackend() {
      return new StubBackend();
    }

    @Bean
    ServletRegistrationBean<DispatcherServlet> eagerDispatcher() {
      return childDispatcher("eagerDispatcher", "/eager/*", 1);
    }
  }

  /** Root application with a load-on-startup child dispatcher that has no paid handlers. */
  @SpringBootConfiguration
  @EnableAutoConfiguration
  @Import(MvcPaid.class)
  static class EagerFreeChildApp {

    @Bean
    LightningBackend stubLightningBackend() {
      return new StubBackend();
    }

    @Bean
    ServletRegistrationBean<DispatcherServlet> freeChildDispatcher() {
      var child = new AnnotationConfigWebApplicationContext();
      child.register(ChildFreeController.class);
      var servlet = new DispatcherServlet(child);
      servlet.setDetectAllHandlerMappings(false);
      var registration = new ServletRegistrationBean<>(servlet, "/free-child/*");
      registration.setName("freeChildDispatcher");
      registration.setLoadOnStartup(1);
      return registration;
    }
  }

  private static ServletRegistrationBean<DispatcherServlet> childDispatcher(
      String name, String mapping, int loadOnStartup, Class<?>... extraComponents) {
    var child = new AnnotationConfigWebApplicationContext();
    child.register(ChildPaidController.class);
    var servlet = new DispatcherServlet(child);
    if (extraComponents.length > 0) {
      child.register(extraComponents);
    } else {
      servlet.setDetectAllHandlerMappings(false);
    }
    var registration = new ServletRegistrationBean<>(servlet, mapping);
    registration.setName(name);
    registration.setLoadOnStartup(loadOnStartup);
    return registration;
  }

  @RestController
  static class MvcPaid {
    @PaymentRequired(priceSats = 10)
    @GetMapping("/mvc/paid")
    String paid() {
      return "PAID-MVC";
    }

    @PaymentRequired(priceSats = 10)
    @GetMapping("/mvc/item/{id}")
    String item(@PathVariable("id") String id) {
      return "PAID-ITEM id=" + id;
    }

    @PaymentRequired(priceSats = 10)
    @DeleteMapping("/mvc/res")
    String deleteRes() {
      return "PAID-MVC-DELETE";
    }

    @PostMapping("/mvc/res")
    String postRes() {
      return "FREE-POST";
    }

    @GetMapping("/mvc/free")
    String free() {
      return "FREE";
    }
  }

  /** Paid annotation declared on an interface method. */
  interface PaidApi {
    @PaymentRequired(priceSats = 10)
    @GetMapping("/iface/paid")
    String paid();
  }

  @RestController
  static class InterfacePaidImpl implements PaidApi {
    @Override
    public String paid() {
      return "PAID-IFACE";
    }
  }

  @Controller
  static class ForwardingController {
    @GetMapping("/mvc/view-forward")
    String viewForward() {
      return "forward:/mvc/paid";
    }

    @GetMapping("/mvc/dispatch-forward")
    @ResponseBody
    String dispatchForward(HttpServletRequest request, HttpServletResponse response)
        throws Exception {
      request.getRequestDispatcher("/mvc/paid").forward(request, response);
      return null;
    }

    @GetMapping("/mvc/include")
    @ResponseBody
    String include(HttpServletRequest request, HttpServletResponse response) throws Exception {
      request.getRequestDispatcher("/mvc/paid").include(request, response);
      return null;
    }
  }

  @Configuration
  @EnableWebMvc
  static class ChildWebMvcConfig {}

  @RestController
  static class ChildPaidController {
    @PaymentRequired(priceSats = 10)
    @GetMapping("/paid")
    String paid() {
      return "PAID-CHILD-DISPATCHER";
    }
  }

  @RestController
  static class ChildFreeController {
    @GetMapping("/free")
    String free() {
      return "FREE-CHILD";
    }
  }

  static final class StubBackend implements LightningBackend {
    @Override
    public Invoice createInvoice(long amountSats, String memo) {
      byte[] paymentHash = new byte[32];
      new SecureRandom().nextBytes(paymentHash);
      Instant now = Instant.now();
      return new Invoice(
          paymentHash,
          "lnbc" + amountSats + "n1pstub",
          amountSats,
          memo,
          InvoiceStatus.PENDING,
          null,
          now,
          now.plus(1, ChronoUnit.HOURS));
    }

    @Override
    public Invoice lookupInvoice(byte[] paymentHash) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isHealthy() {
      return true;
    }
  }

  record Result(int status, String bodyHead) {
    boolean bypass() {
      return status == 200 && bodyHead.startsWith("PAID-");
    }
  }

  private Result raw(String method, String rawPath) throws Exception {
    return raw(port, method, rawPath);
  }

  private static Result raw(int port, String method, String rawPath) throws Exception {
    return exchange(
        port,
        method
            + " "
            + rawPath
            + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nAccept: */*\r\n\r\n");
  }

  private Result rawForm(String rawPath, String form) throws Exception {
    return exchange(
        port,
        "POST "
            + rawPath
            + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
            + "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: "
            + form.length()
            + "\r\n\r\n"
            + form);
  }

  private static Result exchange(int port, String request) throws Exception {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(5000);
      socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
      socket.getOutputStream().flush();
      var buffer = new ByteArrayOutputStream();
      socket.getInputStream().transferTo(buffer);
      String response = buffer.toString(StandardCharsets.ISO_8859_1);
      int status = Integer.parseInt(response.substring(9, 12));
      int bodyStart = response.indexOf("\r\n\r\n");
      String body = bodyStart >= 0 ? response.substring(bodyStart + 4) : "";
      if (response.toLowerCase(Locale.ROOT).contains("transfer-encoding: chunked")) {
        int newline = body.indexOf("\r\n");
        if (newline >= 0) {
          body = body.substring(newline + 2);
        }
        int trailer = body.indexOf("\r\n");
        if (trailer >= 0) {
          body = body.substring(0, trailer);
        }
      }
      return new Result(status, body.substring(0, Math.min(body.length(), 60)));
    }
  }
}
