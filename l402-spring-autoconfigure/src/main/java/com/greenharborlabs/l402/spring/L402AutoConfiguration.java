package com.greenharborlabs.l402.spring;

import com.greenharborlabs.l402.core.credential.CredentialStore;
import com.greenharborlabs.l402.core.credential.InMemoryCredentialStore;
import com.greenharborlabs.l402.core.lightning.LightningBackend;
import com.greenharborlabs.l402.core.macaroon.CaveatVerifier;
import com.greenharborlabs.l402.core.macaroon.FileBasedRootKeyStore;
import com.greenharborlabs.l402.core.macaroon.InMemoryRootKeyStore;
import com.greenharborlabs.l402.core.macaroon.RootKeyStore;
import com.greenharborlabs.l402.core.macaroon.ServicesCaveatVerifier;
import com.greenharborlabs.l402.core.macaroon.ValidUntilCaveatVerifier;
import com.greenharborlabs.l402.core.protocol.L402Validator;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
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
public class L402AutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public L402Properties l402Properties(Environment environment) {
        return Binder.get(environment)
                .bindOrCreate("l402", L402Properties.class);
    }

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
        CredentialStore credentialStore(L402Properties properties) {
            return new InMemoryCredentialStore(properties.getCredentialCacheMaxSize());
        }
    }

    @Bean
    @ConditionalOnMissingBean(name = "caveatVerifiers")
    public List<CaveatVerifier> caveatVerifiers(L402Properties properties) {
        String svcName = properties.getServiceName();
        if (svcName == null || svcName.isBlank()) {
            svcName = "default";
        }
        return List.of(new ServicesCaveatVerifier(), new ValidUntilCaveatVerifier(svcName));
    }

    @Bean
    @ConditionalOnMissingBean
    public L402Validator l402Validator(RootKeyStore rootKeyStore,
                                       CredentialStore credentialStore,
                                       List<CaveatVerifier> caveatVerifiers,
                                       L402Properties properties) {
        String serviceName = properties.getServiceName();
        if (serviceName == null || serviceName.isBlank()) {
            serviceName = "default";
        }
        return new L402Validator(rootKeyStore, credentialStore, caveatVerifiers, serviceName);
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
                                                  L402Validator l402Validator,
                                                  ApplicationContext applicationContext,
                                                  L402Properties properties,
                                                  @Autowired(required = false) L402Metrics l402Metrics,
                                                  L402EarningsTracker l402EarningsTracker) {
        var filter = new L402SecurityFilter(registry, lightningBackend, rootKeyStore,
                l402Validator, applicationContext, properties.getServiceName(), properties);
        if (l402Metrics != null) {
            filter.setMetrics(l402Metrics);
        }
        filter.setEarningsTracker(l402EarningsTracker);
        return filter;
    }

    @Bean
    public FilterRegistrationBean<L402SecurityFilter> l402SecurityFilterRegistration(
            L402SecurityFilter l402SecurityFilter) {
        var registration = new FilterRegistrationBean<>(l402SecurityFilter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean
    public L402EarningsTracker l402EarningsTracker() {
        return new L402EarningsTracker();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "l402.health-cache.enabled", havingValue = "true", matchIfMissing = true)
    static class HealthCacheConfiguration {

        @Bean
        static org.springframework.beans.factory.config.BeanPostProcessor cachingLightningBackendPostProcessor(
                org.springframework.core.env.Environment environment) {
            return new org.springframework.beans.factory.config.BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof LightningBackend backend
                            && !(bean instanceof CachingLightningBackendWrapper)
                            && !(bean instanceof TestModeLightningBackend)) {
                        int ttlSeconds = Binder.get(environment)
                                .bind("l402.health-cache.ttl-seconds", Integer.class)
                                .orElse(5);
                        return new CachingLightningBackendWrapper(
                                backend,
                                java.time.Duration.ofSeconds(ttlSeconds));
                    }
                    return bean;
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "l402.backend", havingValue = "lnbits")
    @ConditionalOnClass(name = "com.greenharborlabs.l402.lightning.lnbits.LnbitsBackend")
    static class LnbitsBackendConfiguration {

        @Bean
        @ConditionalOnMissingBean(LightningBackend.class)
        LightningBackend lightningBackend(L402Properties properties,
                                          com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
            var lnbits = properties.getLnbits();
            var config = new com.greenharborlabs.l402.lightning.lnbits.LnbitsConfig(
                    lnbits.getUrl(), lnbits.getApiKey());
            return new com.greenharborlabs.l402.lightning.lnbits.LnbitsBackend(
                    config,
                    objectMapper,
                    java.net.http.HttpClient.newBuilder()
                            .connectTimeout(java.time.Duration.ofSeconds(10))
                            .build());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "l402.backend", havingValue = "lnd")
    @ConditionalOnClass(name = "com.greenharborlabs.l402.lightning.lnd.LndBackend")
    static class LndBackendConfiguration {

        @Bean(destroyMethod = "shutdown")
        @ConditionalOnMissingBean(io.grpc.ManagedChannel.class)
        io.grpc.ManagedChannel lndManagedChannel(L402Properties properties) {
            return buildChannel(properties.getLnd());
        }

        @Bean
        @ConditionalOnMissingBean(LightningBackend.class)
        LightningBackend lightningBackend(L402Properties properties,
                                          io.grpc.ManagedChannel lndManagedChannel) {
            var lnd = properties.getLnd();
            var config = new com.greenharborlabs.l402.lightning.lnd.LndConfig(
                    lnd.getHost(), lnd.getPort(), lnd.getTlsCertPath(), lnd.getMacaroonPath());
            return new com.greenharborlabs.l402.lightning.lnd.LndBackend(config, lndManagedChannel);
        }

        private static io.grpc.ManagedChannel buildChannel(L402Properties.Lnd lnd) {
            if (lnd.getTlsCertPath() == null) {
                // Plaintext channel for dev/test environments
                return io.grpc.ManagedChannelBuilder
                        .forAddress(lnd.getHost(), lnd.getPort())
                        .usePlaintext()
                        .build();
            }
            try {
                // TLS channel with optional macaroon credentials
                io.grpc.netty.shaded.io.netty.handler.ssl.SslContext sslContext;
                try (java.io.InputStream certStream = java.nio.file.Files.newInputStream(
                        Path.of(lnd.getTlsCertPath()))) {
                    sslContext = io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts.forClient()
                            .trustManager(certStream)
                            .build();
                }
                var builder = io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
                        .forAddress(lnd.getHost(), lnd.getPort())
                        .sslContext(sslContext);
                if (lnd.getMacaroonPath() != null) {
                    byte[] macaroonBytes = java.nio.file.Files.readAllBytes(
                            Path.of(lnd.getMacaroonPath()));
                    String macaroonHex = java.util.HexFormat.of().formatHex(macaroonBytes);
                    builder.intercept(new MacaroonClientInterceptor(macaroonHex));
                }
                return builder.build();
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Failed to build LND gRPC channel", e);
            }
        }
    }
}
