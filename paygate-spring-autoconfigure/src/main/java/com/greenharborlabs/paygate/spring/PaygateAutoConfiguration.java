package com.greenharborlabs.paygate.spring;

import com.greenharborlabs.paygate.core.credential.CredentialCacheEvictionListener;
import com.greenharborlabs.paygate.core.credential.CredentialStore;
import com.greenharborlabs.paygate.core.credential.InMemoryCredentialStore;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.lightning.lnd.LndChannelFactory;
import com.greenharborlabs.paygate.lightning.lnd.LndConfig;
import com.greenharborlabs.paygate.core.macaroon.CaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.FileBasedRootKeyStore;
import com.greenharborlabs.paygate.core.macaroon.InMemoryRootKeyStore;
import com.greenharborlabs.paygate.core.macaroon.ObservableRootKeyStore;
import com.greenharborlabs.paygate.core.macaroon.RootKeyStore;
import com.greenharborlabs.paygate.core.macaroon.CapabilitiesCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.ServicesCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.ValidUntilCaveatVerifier;
import com.greenharborlabs.paygate.core.protocol.L402Validator;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.nio.file.Path;
import java.util.List;

/**
 * Auto-configuration for L402 payment authentication.
 *
 * <p>Activated when {@code paygate.enabled=true} is set in application properties.
 * All beans are guarded with {@link ConditionalOnMissingBean} so that users
 * can override any component.
 */
@AutoConfiguration
@ConditionalOnProperty(name = "paygate.enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(PaygateProperties.class)
public class PaygateAutoConfiguration {

    private static final System.Logger log = System.getLogger(PaygateAutoConfiguration.class.getName());

    @Bean
    PaygateSecurityModeStartupValidator paygateSecurityModeStartupValidator(Environment environment) {
        String configured = PaygateSecurityModeResolver.getConfiguredMode(environment);
        PaygateSecurityModeResolver.validate(configured);
        String resolved = PaygateSecurityModeResolver.resolveFromConfigured(configured);

        log.log(System.Logger.Level.INFO,
                "L402 security mode resolved to: {0} (configured: {1})", resolved, configured);

        if (PaygateSecurityModeResolver.MODE_SERVLET.equals(resolved)
                && PaygateSecurityModeResolver.isSpringSecurityPresent()) {
            log.log(System.Logger.Level.WARNING,
                    "Spring Security is on the classpath but L402 is using servlet filter mode. "
                            + "L402AuthenticationFilter and L402AuthenticationEntryPoint will not be active.");
        }

        return new PaygateSecurityModeStartupValidator(resolved, configured);
    }

    /**
     * Marker bean that records the resolved security mode and ensures validation
     * runs at startup.
     */
    record PaygateSecurityModeStartupValidator(String resolvedMode, String configuredMode) {
    }

    @Bean
    @ConditionalOnMissingBean
    public RootKeyStore rootKeyStore(PaygateProperties properties) {
        return switch (properties.getRootKeyStore()) {
            case "memory" -> new InMemoryRootKeyStore();
            case "file" -> new FileBasedRootKeyStore(Path.of(resolvePath(properties.getRootKeyStorePath())));
            default -> new FileBasedRootKeyStore(Path.of(resolvePath(properties.getRootKeyStorePath())));
        };
    }

    private static final String DEFAULT_ROOT_KEY_STORE_PATH = "~/.paygate/keys";

    private static String resolvePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return resolvePath(DEFAULT_ROOT_KEY_STORE_PATH);
        }
        String home = System.getProperty("user.home");
        if ("~".equals(rawPath)) {
            return home;
        }
        if (rawPath.startsWith("~/")) {
            return home + rawPath.substring(1);
        }
        return rawPath;
    }

    /**
     * Wraps any {@link RootKeyStore} bean with {@link ObservableRootKeyStore} and
     * registers {@link CredentialCacheEvictionListener} so that root key revocations
     * automatically evict cached credentials. Idempotent: already-wrapped stores
     * are not double-wrapped.
     */
    @Configuration(proxyBeanMethods = false)
    static class RootKeyStoreWrappingConfiguration {

        @Bean
        static org.springframework.beans.factory.config.BeanPostProcessor rootKeyStoreWrappingPostProcessor(
                ObjectProvider<CredentialStore> credentialStoreProvider) {
            return new org.springframework.beans.factory.config.BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof RootKeyStore rootKeyStore)) {
                        return bean;
                    }
                    if (bean instanceof ObservableRootKeyStore) {
                        return bean;
                    }

                    var observable = new ObservableRootKeyStore(rootKeyStore);

                    CredentialStore credentialStore = credentialStoreProvider.getIfAvailable();
                    if (credentialStore != null) {
                        observable.addRevocationListener(
                                new CredentialCacheEvictionListener(credentialStore));
                    } else {
                        System.getLogger(RootKeyStoreWrappingConfiguration.class.getName())
                                .log(System.Logger.Level.WARNING,
                                        "CredentialStore not available; skipping revocation listener registration");
                    }

                    return observable;
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "com.github.benmanes.caffeine.cache.Caffeine")
    static class CaffeineCredentialStoreConfiguration {
        @Bean
        @ConditionalOnMissingBean
        CredentialStore credentialStore(PaygateProperties properties) {
            return new CaffeineCredentialStore(properties.getCredentialCacheMaxSize());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingClass("com.github.benmanes.caffeine.cache.Caffeine")
    static class InMemoryCredentialStoreConfiguration {
        @Bean
        @ConditionalOnMissingBean
        CredentialStore credentialStore(PaygateProperties properties) {
            return new InMemoryCredentialStore(properties.getCredentialCacheMaxSize());
        }
    }

    @Bean
    @ConditionalOnMissingBean(name = "caveatVerifiers")
    public List<CaveatVerifier> caveatVerifiers(PaygateProperties properties) {
        String svcName = properties.getServiceName();
        if (svcName == null || svcName.isBlank()) {
            svcName = "default";
        }
        return List.of(new ServicesCaveatVerifier(), new ValidUntilCaveatVerifier(svcName),
                new CapabilitiesCaveatVerifier(svcName));
    }

    @Bean
    @ConditionalOnMissingBean
    public L402Validator paygateValidator(RootKeyStore rootKeyStore,
                                       CredentialStore credentialStore,
                                       List<CaveatVerifier> caveatVerifiers,
                                       PaygateProperties properties) {
        String serviceName = properties.getServiceName();
        if (serviceName == null || serviceName.isBlank()) {
            serviceName = "default";
        }
        return new L402Validator(rootKeyStore, credentialStore, caveatVerifiers, serviceName);
    }

    @Bean
    @ConditionalOnMissingBean
    public PaygateEndpointRegistry paygateEndpointRegistry(RequestMappingHandlerMapping handlerMapping,
                                                       PaygateProperties properties) {
        var registry = new PaygateEndpointRegistry(properties.getDefaultTimeoutSeconds());
        registry.scanAnnotatedEndpoints(handlerMapping);
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean
    public PaygateRateLimiter paygateRateLimiter(PaygateProperties properties) {
        var rl = properties.getRateLimit();
        return new TokenBucketRateLimiter(rl.getBurstSize(), rl.getRequestsPerSecond());
    }

    @Bean
    @ConditionalOnMissingBean
    public PaygateChallengeService paygateChallengeService(RootKeyStore rootKeyStore,
                                                      LightningBackend lightningBackend,
                                                      PaygateProperties properties,
                                                      ApplicationContext applicationContext,
                                                      @Autowired(required = false) PaygateEarningsTracker paygateEarningsTracker,
                                                      @Autowired(required = false) PaygateRateLimiter paygateRateLimiter) {
        return new PaygateChallengeService(rootKeyStore, lightningBackend,
                properties, applicationContext, paygateEarningsTracker, paygateRateLimiter);
    }

    @Bean
    @ConditionalOnMissingBean
    public PaygateSecurityFilter paygateSecurityFilter(PaygateEndpointRegistry registry,
                                                  L402Validator paygateValidator,
                                                  PaygateChallengeService paygateChallengeService,
                                                  PaygateProperties properties,
                                                  @Autowired(required = false) PaygateMetrics paygateMetrics,
                                                  @Autowired(required = false) PaygateEarningsTracker paygateEarningsTracker,
                                                  @Autowired(required = false) PaygateRateLimiter paygateRateLimiter) {
        return new PaygateSecurityFilter(registry, paygateValidator, paygateChallengeService,
                properties.getServiceName(), paygateMetrics, paygateEarningsTracker, paygateRateLimiter);
    }

    @Bean
    @Conditional(PaygateServletModeCondition.class)
    public FilterRegistrationBean<PaygateSecurityFilter> paygateSecurityFilterRegistration(
            PaygateSecurityFilter paygateSecurityFilter) {
        var registration = new FilterRegistrationBean<>(paygateSecurityFilter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean
    public PaygateEarningsTracker paygateEarningsTracker() {
        return new PaygateEarningsTracker();
    }

    /**
     * Single BeanPostProcessor that applies both timeout enforcement and
     * health-check caching wrappers in the correct order. Using a single
     * processor guarantees the wrapping order:
     * {@code CachingWrapper(TimeoutWrapper(Backend))}.
     *
     * <p>This avoids reliance on BeanPostProcessor ordering semantics
     * between separate {@code @Configuration} classes, which can be
     * unreliable across different Spring context initialization strategies.
     */
    @Configuration(proxyBeanMethods = false)
    static class LightningBackendWrappingConfiguration {

        @Bean
        static org.springframework.beans.factory.config.BeanPostProcessor lightningBackendWrappingPostProcessor(
                org.springframework.core.env.Environment environment) {
            return new org.springframework.beans.factory.config.BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof LightningBackend backend)
                            || bean instanceof CachingLightningBackendWrapper
                            || bean instanceof TimeoutEnforcingLightningBackendWrapper
                            || bean instanceof TestModeLightningBackend) {
                        return bean;
                    }

                    // Apply timeout wrapper (innermost)
                    int timeoutSeconds = Binder.get(environment)
                            .bind("paygate.lightning.timeout-seconds", Integer.class)
                            .orElse(5);
                    LightningBackend wrapped = new TimeoutEnforcingLightningBackendWrapper(backend, timeoutSeconds);

                    // Apply caching wrapper (outermost) if health-cache is enabled
                    boolean healthCacheEnabled = Binder.get(environment)
                            .bind("paygate.health-cache.enabled", Boolean.class)
                            .orElse(true);
                    if (healthCacheEnabled) {
                        int ttlSeconds = Binder.get(environment)
                                .bind("paygate.health-cache.ttl-seconds", Integer.class)
                                .orElse(5);
                        wrapped = new CachingLightningBackendWrapper(
                                wrapped, java.time.Duration.ofSeconds(ttlSeconds));
                    }

                    return wrapped;
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "paygate.backend", havingValue = "lnbits")
    @ConditionalOnClass(name = "com.greenharborlabs.paygate.lightning.lnbits.LnbitsBackend")
    static class LnbitsBackendConfiguration {

        @Bean
        @ConditionalOnMissingBean(LightningBackend.class)
        LightningBackend lightningBackend(PaygateProperties properties,
                                          com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
            var lnbits = properties.getLnbits();
            int timeout = lnbits.getRequestTimeoutSeconds() != null
                    ? lnbits.getRequestTimeoutSeconds()
                    : properties.getLightning().getTimeoutSeconds();
            var config = lnbits.getConnectTimeoutSeconds() != null
                    ? new com.greenharborlabs.paygate.lightning.lnbits.LnbitsConfig(
                            lnbits.getUrl(), lnbits.getApiKey(), timeout, lnbits.getConnectTimeoutSeconds())
                    : new com.greenharborlabs.paygate.lightning.lnbits.LnbitsConfig(
                            lnbits.getUrl(), lnbits.getApiKey(), timeout);
            return new com.greenharborlabs.paygate.lightning.lnbits.LnbitsBackend(
                    config,
                    objectMapper,
                    java.net.http.HttpClient.newBuilder()
                            .connectTimeout(java.time.Duration.ofSeconds(config.connectTimeoutSeconds()))
                            .build());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "paygate.backend", havingValue = "lnd")
    @ConditionalOnClass(name = "com.greenharborlabs.paygate.lightning.lnd.LndBackend")
    static class LndBackendConfiguration {

        @Bean
        @ConditionalOnMissingBean(LndConfig.class)
        LndConfig lndConfig(PaygateProperties properties) {
            var lnd = properties.getLnd();
            int rpcDeadline = lnd.getRpcDeadlineSeconds() != null
                    ? lnd.getRpcDeadlineSeconds()
                    : properties.getLightning().getTimeoutSeconds();
            return new LndConfig(
                    lnd.getHost(),
                    lnd.getPort(),
                    lnd.getTlsCertPath(),
                    lnd.getMacaroonPath(),
                    lnd.isAllowPlaintext(),
                    lnd.getKeepAliveTimeSeconds(),
                    lnd.getKeepAliveTimeoutSeconds(),
                    lnd.getIdleTimeoutMinutes(),
                    lnd.getMaxInboundMessageSize(),
                    rpcDeadline
            );
        }

        @Bean(destroyMethod = "shutdown")
        @ConditionalOnMissingBean(io.grpc.ManagedChannel.class)
        io.grpc.ManagedChannel lndManagedChannel(LndConfig lndConfig) {
            return LndChannelFactory.create(lndConfig);
        }

        @Bean
        @ConditionalOnMissingBean(LightningBackend.class)
        LightningBackend lightningBackend(io.grpc.ManagedChannel lndManagedChannel, LndConfig lndConfig) {
            return new com.greenharborlabs.paygate.lightning.lnd.LndBackend(lndManagedChannel, lndConfig);
        }
    }
}
