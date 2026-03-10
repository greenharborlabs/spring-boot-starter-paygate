package com.greenharborlabs.l402.spring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that test-mode is rejected when a production profile is active.
 *
 * <p>Per R-014: if any Spring profile named "production" or "prod" is active
 * AND {@code l402.test-mode=true}, the application must fail to start with
 * an {@link IllegalStateException}. This prevents test-mode credentials from
 * accidentally being used in production environments.
 *
 * <p>TDD: this test references {@link TestModeAutoConfiguration} which does
 * not yet exist — it is expected to fail compilation until that class is
 * implemented.
 */
@DisplayName("Test-mode production guard (R-014)")
class TestModeProductionGuardTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    L402AutoConfiguration.class,
                    TestModeAutoConfiguration.class
            ))
            .withBean(RequestMappingHandlerMapping.class, RequestMappingHandlerMapping::new)
            .withPropertyValues("l402.enabled=true", "l402.test-mode=true");

    @Test
    @DisplayName("test-mode + 'production' profile → IllegalStateException at startup")
    void testModeWithProductionProfileFails() {
        contextRunner
                .withPropertyValues("spring.profiles.active=production")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("test");
                });
    }

    @Test
    @DisplayName("test-mode + 'prod' profile → IllegalStateException at startup")
    void testModeWithProdProfileFails() {
        contextRunner
                .withPropertyValues("spring.profiles.active=prod")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("test");
                });
    }

    @Test
    @DisplayName("test-mode + non-production profile → context starts successfully")
    void testModeWithNonProductionProfileSucceeds() {
        contextRunner
                .withPropertyValues("spring.profiles.active=dev")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                });
    }
}
