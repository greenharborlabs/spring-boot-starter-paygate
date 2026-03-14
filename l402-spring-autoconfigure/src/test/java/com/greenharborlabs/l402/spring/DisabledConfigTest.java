package com.greenharborlabs.l402.spring;

import com.greenharborlabs.l402.core.credential.CredentialStore;
import com.greenharborlabs.l402.core.lightning.LightningBackend;
import com.greenharborlabs.l402.core.macaroon.RootKeyStore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that no L402 beans are created when the auto-configuration is disabled.
 *
 * <p>{@link L402AutoConfiguration} is guarded by
 * {@code @ConditionalOnProperty(name = "l402.enabled", havingValue = "true", matchIfMissing = false)},
 * so the entire configuration class — and all beans it declares — must be absent when
 * {@code l402.enabled=false} or the property is not set at all.
 */
@DisplayName("L402AutoConfiguration disabled")
class DisabledConfigTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    L402AutoConfiguration.class,
                    TestModeAutoConfiguration.class
            ));

    @Test
    @DisplayName("no L402 beans when l402.enabled=false")
    void noBeansWhenExplicitlyDisabled() {
        contextRunner
                .withPropertyValues("l402.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(RootKeyStore.class);
                    assertThat(context).doesNotHaveBean(CredentialStore.class);
                    assertThat(context).doesNotHaveBean(L402SecurityFilter.class);
                    assertThat(context).doesNotHaveBean(L402EndpointRegistry.class);
                    assertThat(context).doesNotHaveBean(L402AutoConfiguration.class);
                });
    }

    @Test
    @DisplayName("no L402 beans when l402.enabled property is absent")
    void noBeansWhenPropertyNotSet() {
        contextRunner
                .run(context -> {
                    assertThat(context).doesNotHaveBean(RootKeyStore.class);
                    assertThat(context).doesNotHaveBean(CredentialStore.class);
                    assertThat(context).doesNotHaveBean(L402SecurityFilter.class);
                    assertThat(context).doesNotHaveBean(L402EndpointRegistry.class);
                    assertThat(context).doesNotHaveBean(L402AutoConfiguration.class);
                });
    }

    @Test
    @DisplayName("no test-mode beans when l402.test-mode=true but l402.enabled=false")
    void noTestModeBeansWhenMasterSwitchDisabled() {
        contextRunner
                .withPropertyValues("l402.enabled=false", "l402.test-mode=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(LightningBackend.class);
                    assertThat(context).doesNotHaveBean(TestModeLightningBackend.class);
                    assertThat(context).doesNotHaveBean(TestModeAutoConfiguration.class);
                });
    }

    @Test
    @DisplayName("no test-mode beans when l402.test-mode=true and l402.enabled is absent")
    void noTestModeBeansWhenMasterSwitchAbsent() {
        contextRunner
                .withPropertyValues("l402.test-mode=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(LightningBackend.class);
                    assertThat(context).doesNotHaveBean(TestModeLightningBackend.class);
                    assertThat(context).doesNotHaveBean(TestModeAutoConfiguration.class);
                });
    }
}
