package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.http.server.PathContainer;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Regression tests for SR-2026-10-18-13: a trailing {@code **} or {@code {*x}} segment also matches
 * zero segments, so a paid pattern and an unprotected pattern of different lengths can still
 * address the same request. Such pairs must be reported, so the overlap policy can warn or fail
 * startup.
 */
@DisplayName("Route overlap with a zero-segment catch-all")
class RouteOverlapZeroSegmentCatchAllTest {

  private static final PathPatternParser PARSER = new PathPatternParser();

  @ParameterizedTest(name = "paid {0} and unprotected {1} both match {2}")
  @CsvSource({
    "/api/**, /api, /api",
    "/api/{*rest}, /api, /api",
    "/reports, /reports/**, /reports",
    "/reports, /{section}/**, /reports",
    "/api/items, /api/items/{*rest}, /api/items",
    "/a/b/**, /a/{x}, /a/b",
    "/api/**, /api/admin, /api/admin",
    "/**, /, /"
  })
  void reportsEveryShapeThatSpringMatchesOnACommonRequest(
      String paid, String unprotected, String sharedRequest) {
    var path = PathContainer.parsePath(sharedRequest);
    assertThat(PARSER.parse(paid).matches(path)).isTrue();
    assertThat(PARSER.parse(unprotected).matches(path)).isTrue();

    assertThat(RouteOverlapAnalyzer.analyze("GET", paid, "GET", unprotected))
        .isNotNull()
        .extracting(RouteOverlapAnalyzer.Finding::classification)
        .isEqualTo(RouteOverlapAnalyzer.Classification.POSSIBLE_OVERLAP);
  }

  @ParameterizedTest(name = "paid {0} and unprotected {1} are disjoint")
  @CsvSource({"/reports, /reports/x/**", "/api/**, /apix", "/a/b/**, /a/c", "/a/b/c/**, /a/b"})
  void doesNotReportACatchAllThatNeedsMoreThanZeroSegments(String paid, String unprotected) {
    assertThat(RouteOverlapAnalyzer.analyze("GET", paid, "GET", unprotected)).isNull();
    assertThat(RouteOverlapAnalyzer.analyze("GET", unprotected, "GET", paid)).isNull();
  }

  @ParameterizedTest(name = "paid {0} vs unprotected {1} warns by default")
  @CsvSource({"/api/**, /api", "/reports, /reports/**", "/a/b/**, /a/{x}"})
  void reviewShapesWarnByDefault(String paid, String unprotected) {
    var registry = new PaygateEndpointRegistry(600);
    registry.register(new PaygateEndpointConfig("GET", paid, 10, 600, "", "", ""));
    registry.scanAnnotatedEndpoints(unprotectedMapping(unprotected));

    assertThatCode(registry::validateManualRouteOverlaps).doesNotThrowAnyException();
  }

  @ParameterizedTest(name = "paid {0} vs unprotected {1} fails under FAIL")
  @CsvSource({"/api/**, /api", "/reports, /reports/**", "/a/b/**, /a/{x}"})
  void reviewShapesFailUnderFailPolicy(String paid, String unprotected) {
    var registry = new PaygateEndpointRegistry(600, 50, PaygateProperties.OverlapPolicy.FAIL);
    registry.register(new PaygateEndpointConfig("GET", paid, 10, 600, "", "", ""));
    registry.scanAnnotatedEndpoints(unprotectedMapping(unprotected));

    assertThatThrownBy(registry::validateManualRouteOverlaps)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Possible paid/unprotected route overlap")
        .hasMessageContaining("paid=" + paid)
        .hasMessageContaining("unprotected=" + unprotected);
  }

  @Test
  @DisplayName("application startup fails under overlap-policy=FAIL for paid /api/** vs free /api")
  void applicationStartupFailsUnderFailPolicy() {
    contextRunner()
        .withPropertyValues("paygate.routing.overlap-policy=FAIL")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessageContaining("Possible paid/unprotected route overlap")
                  .hasMessageContaining("paid=/api/**")
                  .hasMessageContaining("unprotected=/api");
            });
  }

  @Test
  @DisplayName("application starts under the default WARN policy for paid /api/** vs free /api")
  void applicationStartsUnderDefaultWarnPolicy() {
    contextRunner().run(context -> assertThat(context).hasNotFailed());
  }

  private static WebApplicationContextRunner contextRunner() {
    return new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PaygateAutoConfiguration.class))
        .withPropertyValues(
            "paygate.enabled=true", "paygate.backend=lnbits", "paygate.root-key-store=memory")
        .withBean(LightningBackend.class, () -> mock(LightningBackend.class))
        .withBean(
            "requestMappingHandlerMapping",
            RequestMappingHandlerMapping.class,
            () -> unprotectedMapping("/api"))
        .withBean(
            "manualPaidRoutes",
            SmartInitializingSingleton.class,
            () -> new ManualPaidRouteRegistrar("/api/**"));
  }

  private static RequestMappingHandlerMapping unprotectedMapping(String path) {
    var mapping = mock(RequestMappingHandlerMapping.class);
    when(mapping.getHandlerMethods())
        .thenReturn(
            Map.of(
                RequestMappingInfo.paths(path).methods(RequestMethod.GET).build(),
                mock(HandlerMethod.class)));
    return mapping;
  }

  /** Registers a manual paid route the documented way, before the auto-configured scan runs. */
  private static final class ManualPaidRouteRegistrar
      implements SmartInitializingSingleton, ApplicationContextAware {

    private final String pattern;
    private ApplicationContext context;

    ManualPaidRouteRegistrar(String pattern) {
      this.pattern = pattern;
    }

    @Override
    public void setApplicationContext(ApplicationContext context) {
      this.context = context;
    }

    @Override
    public void afterSingletonsInstantiated() {
      context
          .getBean(PaygateEndpointRegistry.class)
          .register(new PaygateEndpointConfig("GET", pattern, 10, 600, "", "", ""));
    }
  }
}
