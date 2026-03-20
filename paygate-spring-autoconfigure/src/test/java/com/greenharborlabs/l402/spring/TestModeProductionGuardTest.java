package com.greenharborlabs.l402.spring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the two-layer test-mode guard (R-014):
 *
 * <ol>
 *   <li><b>Denylist:</b> "production" or "prod" profiles always reject test mode.</li>
 *   <li><b>Allowlist:</b> at least one of "test", "dev", "local", "development" must be active.</li>
 * </ol>
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

    // --- Denylist checks ---

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
                            .hasMessageContaining("production profiles");
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
                            .hasMessageContaining("production profiles");
                });
    }

    @Test
    @DisplayName("test-mode + 'production' AND 'dev' profiles → still fails (denylist wins)")
    void testModeWithProductionAndDevProfilesFails() {
        contextRunner
                .withPropertyValues("spring.profiles.active=production,dev")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("production profiles");
                });
    }

    // --- Allowlist checks ---

    @Test
    @DisplayName("test-mode + 'dev' profile → context starts successfully")
    void testModeWithDevProfileSucceeds() {
        contextRunner
                .withPropertyValues("spring.profiles.active=dev")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                });
    }

    @Test
    @DisplayName("test-mode + 'test' profile → context starts successfully")
    void testModeWithTestProfileSucceeds() {
        contextRunner
                .withPropertyValues("spring.profiles.active=test")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                });
    }

    @Test
    @DisplayName("test-mode + 'local' profile → context starts successfully")
    void testModeWithLocalProfileSucceeds() {
        contextRunner
                .withPropertyValues("spring.profiles.active=local")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                });
    }

    @Test
    @DisplayName("test-mode + 'development' profile → context starts successfully")
    void testModeWithDevelopmentProfileSucceeds() {
        contextRunner
                .withPropertyValues("spring.profiles.active=development")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                });
    }

    // --- Unknown / custom production profiles ---

    @Test
    @DisplayName("test-mode + unknown profile 'prd' without dev/test → fails (allowlist)")
    void testModeWithUnknownProfilePrdFails() {
        contextRunner
                .withPropertyValues("spring.profiles.active=prd")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("requires an explicit dev/test profile");
                });
    }

    @Test
    @DisplayName("test-mode + no active profiles → fails (allowlist)")
    void testModeWithNoProfilesFails() {
        contextRunner
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("requires an explicit dev/test profile");
                });
    }

    @Test
    @DisplayName("test-mode + 'staging' without dev/test → fails (allowlist)")
    void testModeWithStagingProfileFails() {
        contextRunner
                .withPropertyValues("spring.profiles.active=staging")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("requires an explicit dev/test profile");
                });
    }
}
