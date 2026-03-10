package com.greenharborlabs.l402.spring;

import com.greenharborlabs.l402.core.credential.CredentialStore;
import com.greenharborlabs.l402.core.credential.InMemoryCredentialStore;
import com.greenharborlabs.l402.core.lightning.LightningBackend;
import com.greenharborlabs.l402.core.macaroon.CaveatVerifier;
import com.greenharborlabs.l402.core.macaroon.FileBasedRootKeyStore;
import com.greenharborlabs.l402.core.macaroon.InMemoryRootKeyStore;
import com.greenharborlabs.l402.core.macaroon.RootKeyStore;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.nio.file.Path;
import java.util.List;

/**
 * Auto-configuration for L402 payment authentication.
 *
 * <p>Activated when {@code l402.enabled=true} is set in application properties.
 * All beans are guarded with {@link ConditionalOnMissingBean} so that users
 * can override any component.
 */
@AutoConfiguration
@ConditionalOnProperty(name = "l402.enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(L402Properties.class)
public class L402AutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RootKeyStore rootKeyStore(L402Properties properties) {
        return switch (properties.getRootKeyStore()) {
            case "memory" -> new InMemoryRootKeyStore();
            case "file" -> new FileBasedRootKeyStore(Path.of(
                    properties.getRootKeyStorePath().replace("~", System.getProperty("user.home"))));
            default -> new FileBasedRootKeyStore(Path.of(
                    properties.getRootKeyStorePath().replace("~", System.getProperty("user.home"))));
        };
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "com.github.benmanes.caffeine.cache.Caffeine")
    static class CaffeineCredentialStoreConfiguration {
        @Bean
        @ConditionalOnMissingBean
        CredentialStore credentialStore(L402Properties properties) {
            return new CaffeineCredentialStore(properties.getCredentialCacheMaxSize());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingClass("com.github.benmanes.caffeine.cache.Caffeine")
    static class InMemoryCredentialStoreConfiguration {
        @Bean
        @ConditionalOnMissingBean
        CredentialStore credentialStore() {
            return new InMemoryCredentialStore();
        }
    }

    @Bean
    @ConditionalOnMissingBean(name = "caveatVerifiers")
    public List<CaveatVerifier> caveatVerifiers() {
        return List.of();
    }

    @Bean
    @ConditionalOnMissingBean
    public L402EndpointRegistry l402EndpointRegistry(RequestMappingHandlerMapping handlerMapping) {
        var registry = new L402EndpointRegistry();
        registry.scanAnnotatedEndpoints(handlerMapping);
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean
    public L402SecurityFilter l402SecurityFilter(L402EndpointRegistry registry,
                                                  LightningBackend lightningBackend,
                                                  RootKeyStore rootKeyStore,
                                                  CredentialStore credentialStore,
                                                  List<CaveatVerifier> caveatVerifiers,
                                                  L402Properties properties) {
        String serviceName = properties.getServiceName();
        if (serviceName == null || serviceName.isBlank()) {
            serviceName = "default";
        }
        return new L402SecurityFilter(registry, lightningBackend, rootKeyStore,
                credentialStore, caveatVerifiers, serviceName);
    }

    @Bean
    public FilterRegistrationBean<L402SecurityFilter> l402SecurityFilterRegistration(
            L402SecurityFilter l402SecurityFilter) {
        var registration = new FilterRegistrationBean<>(l402SecurityFilter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
