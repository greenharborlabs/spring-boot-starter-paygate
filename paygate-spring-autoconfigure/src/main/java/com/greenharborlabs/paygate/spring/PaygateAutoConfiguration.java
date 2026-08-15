package com.greenharborlabs.paygate.spring;

import com.greenharborlabs.paygate.api.PaymentProtocol;
import com.greenharborlabs.paygate.core.credential.CredentialCacheEvictionListener;
import com.greenharborlabs.paygate.core.credential.CredentialStore;
import com.greenharborlabs.paygate.core.credential.InMemoryCredentialStore;
import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import com.greenharborlabs.paygate.core.macaroon.CapabilitiesCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.CaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.ClientIpCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.FileBasedRootKeyStore;
import com.greenharborlabs.paygate.core.macaroon.InMemoryRootKeyStore;
import com.greenharborlabs.paygate.core.macaroon.MethodCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.ObservableRootKeyStore;
import com.greenharborlabs.paygate.core.macaroon.PathCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.RootKeyStore;
import com.greenharborlabs.paygate.core.macaroon.RouteCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.ServicesCaveatVerifier;
import com.greenharborlabs.paygate.core.macaroon.ValidUntilCaveatVerifier;
import com.greenharborlabs.paygate.core.protocol.L402Validator;
import com.greenharborlabs.paygate.lightning.lnd.LndChannelFactory;
import com.greenharborlabs.paygate.lightning.lnd.LndConfig;
import com.greenharborlabs.paygate.protocol.l402.L402Protocol;
import jakarta.servlet.DispatcherType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Auto-configuration for L402 payment authentication.
 *
 * <p>Activated when {@code paygate.enabled=true} is set in application properties. All beans are
 * guarded with {@link ConditionalOnMissingBean} so that users can override any component.
 */
@AutoConfiguration
@ConditionalOnProperty(name = "paygate.enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(PaygateProperties.class)
public class PaygateAutoConfiguration {

  private static final System.Logger log =
      System.getLogger(PaygateAutoConfiguration.class.getName());

  @Bean
  PaygateSecurityModeStartupValidator paygateSecurityModeStartupValidator(Environment environment) {
    String configured = PaygateSecurityModeResolver.getConfiguredMode(environment);
    PaygateSecurityModeResolver.validate(configured);
    String resolved = PaygateSecurityModeResolver.resolveFromConfigured(configured);

    log.log(
        System.Logger.Level.INFO,
        "L402 security mode resolved to: {0} (configured: {1})",
        resolved,
        configured);

    if (PaygateSecurityModeResolver.MODE_SERVLET.equals(resolved)
        && PaygateSecurityModeResolver.isSpringSecurityPresent()) {
      log.log(
          System.Logger.Level.WARNING,
          "Spring Security is on the classpath but L402 is using servlet filter mode. "
              + "L402AuthenticationFilter and L402AuthenticationEntryPoint will not be active.");
    }

    return new PaygateSecurityModeStartupValidator(resolved, configured);
  }

  /** Marker bean that records the resolved security mode and ensures validation runs at startup. */
  record PaygateSecurityModeStartupValidator(String resolvedMode, String configuredMode) {}

  @Bean
  @ConditionalOnMissingBean
  public RootKeyStore rootKeyStore(PaygateProperties properties) {
    return switch (properties.getRootKeyStore()) {
      case "memory" -> new InMemoryRootKeyStore();
      case "file" ->
          new FileBasedRootKeyStore(Path.of(resolvePath(properties.getRootKeyStorePath())));
      default ->
          throw new IllegalStateException(
              "Unsupported root-key-store configuration; use memory or file");
    };
  }

  private static final String DEFAULT_ROOT_KEY_STORE_PATH = "~/.paygate/keys";
  private static final Set<String> UNSAFE_MPP_SAMPLE_SECRETS =
      Set.of("dev-only-mpp-test-secret-do-not-use-in-production");

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
   * Wraps any {@link RootKeyStore} bean with {@link ObservableRootKeyStore} and registers {@link
   * CredentialCacheEvictionListener} so that root key revocations automatically evict cached
   * credentials. Idempotent: already-wrapped stores are not double-wrapped.
   */
  @Configuration(proxyBeanMethods = false)
  static class RootKeyStoreWrappingConfiguration {

    @Bean
    static org.springframework.beans.factory.config.BeanPostProcessor
        rootKeyStoreWrappingPostProcessor(ObjectProvider<CredentialStore> credentialStoreProvider) {
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
            observable.addRevocationListener(new CredentialCacheEvictionListener(credentialStore));
          } else {
            System.getLogger(RootKeyStoreWrappingConfiguration.class.getName())
                .log(
                    System.Logger.Level.WARNING,
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
  @ConditionalOnClass(name = "com.github.benmanes.caffeine.cache.Caffeine")
  static class CaffeineCapabilityCacheConfiguration {
    @Bean
    @ConditionalOnMissingBean
    CapabilityCache capabilityCache(PaygateProperties properties) {
      return new CaffeineCapabilityCache(properties.getCredentialCacheMaxSize());
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
    int maxValues = properties.getCaveat().getMaxValuesPerCaveat();
    return List.of(
        new ServicesCaveatVerifier(maxValues),
        new ValidUntilCaveatVerifier(svcName),
        new CapabilitiesCaveatVerifier(svcName, maxValues),
        new PathCaveatVerifier(maxValues),
        new RouteCaveatVerifier(maxValues),
        new MethodCaveatVerifier(maxValues),
        new ClientIpCaveatVerifier(maxValues));
  }

  @Bean
  @ConditionalOnMissingBean
  public ClientIpResolver clientIpResolver(PaygateProperties properties) {
    return new ClientIpResolver(
        properties.isTrustForwardedHeaders(),
        properties.getTrustedProxyAddresses(),
        properties.getRateLimit().getIpv6PrefixLength());
  }

  @Bean
  @ConditionalOnMissingBean
  public L402Validator paygateValidator(
      RootKeyStore rootKeyStore,
      CredentialStore credentialStore,
      List<CaveatVerifier> caveatVerifiers,
      PaygateProperties properties) {
    String serviceName = properties.getServiceName();
    if (serviceName == null || serviceName.isBlank()) {
      serviceName = "default";
    }
    return new L402Validator(rootKeyStore, credentialStore, caveatVerifiers, serviceName);
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "com.greenharborlabs.paygate.protocol.l402.L402Protocol")
  @ConditionalOnProperty(
      name = "paygate.protocols.l402.enabled",
      havingValue = "true",
      matchIfMissing = true)
  static class L402ProtocolConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "l402Protocol")
    @Order(1)
    PaymentProtocol l402Protocol(L402Validator paygateValidator, PaygateProperties properties) {
      String serviceName = properties.getServiceName();
      if (serviceName == null || serviceName.isBlank()) {
        serviceName = "default";
      }
      return new L402Protocol(paygateValidator, serviceName);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "com.greenharborlabs.paygate.protocol.mpp.MppProtocol")
  @Conditional(PaygateAutoConfiguration.MppEnabledCondition.class)
  static class MppProtocolConfiguration {

    @Bean(destroyMethod = "destroy")
    @ConditionalOnMissingBean(name = "mppChallengeBindingSecret")
    com.greenharborlabs.paygate.api.crypto.SensitiveBytes mppChallengeBindingSecret(
        PaygateProperties properties, ProtocolStartupValidator _validator) {
      String secret = properties.getProtocols().getMpp().getChallengeBindingSecret();
      byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
      return new com.greenharborlabs.paygate.api.crypto.SensitiveBytes(secretBytes);
    }

    @Bean(destroyMethod = "destroy")
    @ConditionalOnMissingBean(name = "mppPreviousChallengeBindingSecret")
    @ConditionalOnProperty(name = "paygate.protocols.mpp.previous-challenge-binding-secret")
    com.greenharborlabs.paygate.api.crypto.SensitiveBytes mppPreviousChallengeBindingSecret(
        PaygateProperties properties, ProtocolStartupValidator _validator) {
      String previousSecret =
          properties.getProtocols().getMpp().getPreviousChallengeBindingSecret();
      if (previousSecret == null || previousSecret.isBlank()) {
        throw new IllegalStateException(
            "paygate.protocols.mpp.previous-challenge-binding-secret is configured but blank. "
                + "Set a non-blank value with at least 32 UTF-8 bytes.");
      }
      byte[] previousSecretBytes = previousSecret.getBytes(StandardCharsets.UTF_8);
      return new com.greenharborlabs.paygate.api.crypto.SensitiveBytes(previousSecretBytes);
    }

    @Bean
    @ConditionalOnMissingBean(name = "mppProtocol")
    @Order(2)
    PaymentProtocol mppProtocol(
        @Qualifier("mppChallengeBindingSecret")
            com.greenharborlabs.paygate.api.crypto.SensitiveBytes mppChallengeBindingSecret,
        @Qualifier("mppPreviousChallengeBindingSecret")
            ObjectProvider<com.greenharborlabs.paygate.api.crypto.SensitiveBytes>
                mppPreviousChallengeBindingSecretProvider,
        PaygateProperties properties,
        ProtocolStartupValidator _validator) {
      var mpp = properties.getProtocols().getMpp();
      var limits =
          new com.greenharborlabs.paygate.protocol.mpp.MppParserLimits(
              mpp.getMaxJsonDepth(),
              mpp.getMaxStringLength(),
              mpp.getMaxKeysPerObject(),
              mpp.getMaxCredentialBytes());
      var previousSecret = mppPreviousChallengeBindingSecretProvider.getIfAvailable();
      return new com.greenharborlabs.paygate.protocol.mpp.MppProtocol(
          mppChallengeBindingSecret, limits, previousSecret);
    }
  }

  /**
   * Condition that determines whether the MPP protocol should be enabled.
   *
   * <ul>
   *   <li>{@code paygate.protocols.mpp.enabled=false} -- not enabled
   *   <li>{@code paygate.protocols.mpp.enabled=true} -- enabled (secret validated by startup
   *       validator)
   *   <li>{@code paygate.protocols.mpp.enabled=auto} (default) -- enabled only if secret is present
   * </ul>
   */
  static class MppEnabledCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      String enabled =
          context.getEnvironment().getProperty("paygate.protocols.mpp.enabled", "auto");
      return switch (enabled.toLowerCase(Locale.ROOT)) {
        case "false" -> false;
        case "true" -> true;
        case "auto" -> {
          String secret =
              context
                  .getEnvironment()
                  .getProperty("paygate.protocols.mpp.challenge-binding-secret");
          yield secret != null && !secret.isBlank();
        }
        default -> false;
      };
    }
  }

  @Bean
  ProtocolStartupValidator protocolStartupValidator(
      PaygateProperties properties, ApplicationContext applicationContext) {
    var mppConfig = properties.getProtocols().getMpp();
    String mppEnabled = mppConfig.getEnabled();
    String secret = mppConfig.getChallengeBindingSecret();
    String previousSecret = mppConfig.getPreviousChallengeBindingSecret();
    boolean secretPresent = hasText(secret);

    validateMppSecrets(mppEnabled, secret, previousSecret, properties.isTestMode());

    // Determine which protocols will be active based on configuration + classpath
    boolean l402OnClasspath =
        isClassPresent(
            "com.greenharborlabs.paygate.protocol.l402.L402Protocol",
            applicationContext.getClassLoader());
    boolean mppOnClasspath =
        isClassPresent(
            "com.greenharborlabs.paygate.protocol.mpp.MppProtocol",
            applicationContext.getClassLoader());
    boolean l402Active = properties.getProtocols().getL402().isEnabled() && l402OnClasspath;
    boolean mppActive = isMppEffectivelyEnabled(mppEnabled, secretPresent) && mppOnClasspath;

    if (!l402Active && !mppActive) {
      if (!l402OnClasspath && !mppOnClasspath) {
        throw new IllegalStateException(
            "No usable payment protocol implementation is present. Check paygate.protocols "
                + "configuration and runtime dependencies.");
      }
      throw new IllegalStateException(
          "No payment protocols are enabled. At least one protocol must be active. "
              + "Enable L402 (paygate.protocols.l402.enabled=true) or MPP "
              + "(paygate.protocols.mpp.enabled=true with a challenge-binding-secret).");
    }

    int count = (l402Active ? 1 : 0) + (mppActive ? 1 : 0);
    return new ProtocolStartupValidator(count);
  }

  private static void validateMppSecrets(
      String mppEnabled, String secret, String previousSecret, boolean testMode) {
    boolean secretPresent = hasText(secret);
    boolean previousSecretPresent = hasText(previousSecret);

    if ("true".equalsIgnoreCase(mppEnabled) && !secretPresent) {
      throw new IllegalStateException(
          "paygate.protocols.mpp.enabled=true but paygate.protocols.mpp.challenge-binding-secret "
              + "is not set. Provide a secret of at least 32 UTF-8 bytes.");
    }

    validateSecretMinBytes("paygate.protocols.mpp.challenge-binding-secret", secret);
    validateNoUnsafeSampleSecret(
        "paygate.protocols.mpp.challenge-binding-secret", secret, testMode);

    if (previousSecretPresent && !secretPresent) {
      throw new IllegalStateException(
          "paygate.protocols.mpp.previous-challenge-binding-secret requires "
              + "paygate.protocols.mpp.challenge-binding-secret to also be set.");
    }

    validateSecretMinBytes(
        "paygate.protocols.mpp.previous-challenge-binding-secret", previousSecret);
    validateNoUnsafeSampleSecret(
        "paygate.protocols.mpp.previous-challenge-binding-secret", previousSecret, testMode);
  }

  private static void validateSecretMinBytes(String propertyName, String secret) {
    if (!hasText(secret)) {
      return;
    }
    int byteLength = secret.getBytes(StandardCharsets.UTF_8).length;
    if (byteLength < 32) {
      throw new IllegalStateException(
          propertyName + " must be at least 32 UTF-8 bytes, got " + byteLength + " bytes.");
    }
  }

  private static void validateNoUnsafeSampleSecret(
      String propertyName, String secret, boolean testMode) {
    if (testMode || !hasText(secret)) {
      return;
    }
    if (UNSAFE_MPP_SAMPLE_SECRETS.contains(secret)) {
      throw new IllegalStateException(
          propertyName
              + " uses a committed sample secret. Committed sample secrets are test-only; "
              + "set PAYGATE_MPP_SECRET or provide a unique secret of at least 32 UTF-8 bytes.");
    }
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private static boolean isMppEffectivelyEnabled(String mppEnabled, boolean secretPresent) {
    return switch (mppEnabled.toLowerCase(Locale.ROOT)) {
      case "true" -> true;
      case "auto" -> secretPresent;
      default -> false;
    };
  }

  private static boolean isClassPresent(String className, ClassLoader classLoader) {
    try {
      Class.forName(className, false, classLoader);
      return true;
    } catch (ClassNotFoundException _) {
      return false;
    }
  }

  record ProtocolStartupValidator(int activeProtocolCount) {}

  @Bean
  @ConditionalOnMissingBean
  public PaygateEndpointRegistry paygateEndpointRegistry(PaygateProperties properties) {
    return new PaygateEndpointRegistry(
        properties.getDefaultTimeoutSeconds(), properties.getCaveat().getMaxValuesPerCaveat());
  }

  /** Scans mappings only after MVC has completed constructing its configuration graph. */
  @Bean
  SmartInitializingSingleton paygateEndpointRegistryScanner(
      PaygateEndpointRegistry registry, Map<String, HandlerMapping> handlerMappings) {
    return () -> {
      int sourceOrder = 0;
      for (var entry : handlerMappings.entrySet()) {
        HandlerMapping handlerMapping = entry.getValue();
        if (handlerMapping instanceof RequestMappingHandlerMapping requestMappingHandlerMapping) {
          registry.scanAnnotatedEndpoints(requestMappingHandlerMapping, sourceOrder++);
        } else {
          rejectUnsupportedPaidHandlerSource(entry.getKey(), handlerMapping);
        }
      }
    };
  }

  private static void rejectUnsupportedPaidHandlerSource(
      String beanName, HandlerMapping handlerMapping) {
    try {
      var request =
          (jakarta.servlet.http.HttpServletRequest)
              java.lang.reflect.Proxy.newProxyInstance(
                  PaygateAutoConfiguration.class.getClassLoader(),
                  new Class<?>[] {jakarta.servlet.http.HttpServletRequest.class},
                  (proxy, method, args) -> {
                    if (method.getName().equals("getMethod")) return "GET";
                    if (method.getName().equals("getRequestURI")) return "/";
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type.isPrimitive()) return 0;
                    return null;
                  });
      HandlerExecutionChain chain = handlerMapping.getHandler(request);
      if (chain != null
          && chain.getHandler()
              instanceof org.springframework.web.method.HandlerMethod handlerMethod
          && handlerMethod.getMethodAnnotation(PaymentRequired.class) != null) {
        throw new IllegalStateException(
            "unsupported HandlerMapping bean '" + beanName + "' returned a paid handler");
      }
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      log.log(
          System.Logger.Level.WARNING,
          "Unable to inspect unsupported HandlerMapping {0}; paid handlers from it are not supported",
          beanName);
    }
  }

  @Bean
  @ConditionalOnMissingBean(PaygateHandlerInterceptor.class)
  public PaygateHandlerInterceptor paygateHandlerInterceptor(PaygateEndpointRegistry registry) {
    return new PaygateHandlerInterceptor(registry);
  }

  @Bean
  @ConditionalOnMissingBean(name = "paygateHandlerInterceptorConfigurer")
  public WebMvcConfigurer paygateHandlerInterceptorConfigurer(
      PaygateHandlerInterceptor paygateHandlerInterceptor) {
    return new WebMvcConfigurer() {
      @Override
      public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(paygateHandlerInterceptor).order(Ordered.LOWEST_PRECEDENCE);
      }
    };
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "com.github.benmanes.caffeine.cache.Caffeine")
  static class CaffeineRateLimiterConfiguration {
    @Bean
    @ConditionalOnMissingBean
    PaygateRateLimiter paygateRateLimiter(PaygateProperties properties) {
      var rl = properties.getRateLimit();
      return new CaffeineTokenBucketRateLimiter(
          rl.getBurstSize(),
          rl.getRequestsPerSecond(),
          rl.getMaxBuckets(),
          System::nanoTime,
          com.github.benmanes.caffeine.cache.Ticker.systemTicker());
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnMissingClass("com.github.benmanes.caffeine.cache.Caffeine")
  static class InMemoryRateLimiterConfiguration {
    @Bean
    @ConditionalOnMissingBean
    PaygateRateLimiter paygateRateLimiter(PaygateProperties properties) {
      var rl = properties.getRateLimit();
      return new TokenBucketRateLimiter(
          rl.getBurstSize(), rl.getRequestsPerSecond(), rl.getMaxBuckets());
    }
  }

  @Bean
  @ConditionalOnMissingBean
  public PaygateChallengeService paygateChallengeService(
      RootKeyStore rootKeyStore,
      LightningBackend lightningBackend,
      PaygateProperties properties,
      ApplicationContext applicationContext,
      @Autowired(required = false) PaygateEarningsTracker paygateEarningsTracker,
      @Autowired(required = false) PaygateRateLimiter paygateRateLimiter,
      @Autowired(required = false) ClientIpResolver clientIpResolver,
      @Autowired(required = false) CapabilityCache capabilityCache,
      org.springframework.beans.factory.ObjectProvider<DevelopmentSafetyPolicy.ValidatedTestMode>
          validatedTestMode) {
    return new PaygateChallengeService(
        rootKeyStore,
        lightningBackend,
        properties,
        applicationContext,
        paygateEarningsTracker,
        paygateRateLimiter,
        clientIpResolver,
        capabilityCache,
        validatedTestMode.getIfAvailable() != null);
  }

  @Bean
  @ConditionalOnMissingBean
  public PaygateSecurityFilter paygateSecurityFilter(
      PaygateEndpointRegistry registry,
      List<PaymentProtocol> protocols,
      PaygateChallengeService paygateChallengeService,
      PaygateProperties properties,
      @Autowired(required = false) ClientIpResolver clientIpResolver,
      @Autowired(required = false) PaygateMetrics paygateMetrics,
      @Autowired(required = false) PaygateEarningsTracker paygateEarningsTracker,
      @Autowired(required = false) PaygateRateLimiter paygateRateLimiter) {
    return new PaygateSecurityFilter(
        registry,
        protocols,
        paygateChallengeService,
        properties.getServiceName(),
        clientIpResolver,
        paygateMetrics,
        paygateEarningsTracker,
        paygateRateLimiter,
        properties.getRequestBody().getMaxBytes());
  }

  @Bean
  @Conditional(PaygateServletModeCondition.class)
  public FilterRegistrationBean<PaygateSecurityFilter> paygateSecurityFilterRegistration(
      PaygateSecurityFilter paygateSecurityFilter) {
    var registration = new FilterRegistrationBean<>(paygateSecurityFilter);
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
    registration.addUrlPatterns("/*");
    registration.setDispatcherTypes(
        DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.FORWARD, DispatcherType.ERROR);
    return registration;
  }

  /**
   * Prevents Spring Boot from auto-registering {@link PaygateSecurityFilter} as a servlet filter
   * when in spring-security mode. Without this, both the servlet filter and the Spring Security
   * {@code PaygateAuthenticationFilter} would process payment credentials, causing conflicts with
   * {@code @PreAuthorize} enforcement.
   */
  @Bean
  @Conditional(PaygateSpringSecurityModeCondition.class)
  public FilterRegistrationBean<PaygateSecurityFilter> paygateSecurityFilterDisabledRegistration(
      PaygateSecurityFilter paygateSecurityFilter) {
    var registration = new FilterRegistrationBean<>(paygateSecurityFilter);
    registration.setEnabled(false);
    return registration;
  }

  @Bean
  @ConditionalOnMissingBean
  public PaygateEarningsTracker paygateEarningsTracker() {
    return new PaygateEarningsTracker();
  }

  /**
   * Single BeanPostProcessor that applies both timeout enforcement and health-check caching
   * wrappers in the correct order. Using a single processor guarantees the wrapping order: {@code
   * CachingWrapper(TimeoutWrapper(Backend))}.
   *
   * <p>This avoids reliance on BeanPostProcessor ordering semantics between separate
   * {@code @Configuration} classes, which can be unreliable across different Spring context
   * initialization strategies.
   */
  @Configuration(proxyBeanMethods = false)
  static class LightningBackendWrappingConfiguration {

    @Bean
    static org.springframework.beans.factory.config.BeanPostProcessor
        lightningBackendWrappingPostProcessor(
            org.springframework.core.env.Environment environment,
            org.springframework.beans.factory.support.DefaultListableBeanFactory beanFactory) {
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
          int timeoutSeconds =
              Binder.get(environment)
                  .bind("paygate.lightning.timeout-seconds", Integer.class)
                  .orElse(5);
          LightningBackend wrapped =
              new TimeoutEnforcingLightningBackendWrapper(backend, timeoutSeconds);
          var timeoutWrapper = (TimeoutEnforcingLightningBackendWrapper) wrapped;
          beanFactory.registerDisposableBean(beanName, timeoutWrapper::close);

          // Apply caching wrapper (outermost) if health-cache is enabled
          boolean healthCacheEnabled =
              Binder.get(environment)
                  .bind("paygate.health-cache.enabled", Boolean.class)
                  .orElse(true);
          if (healthCacheEnabled) {
            int ttlSeconds =
                Binder.get(environment)
                    .bind("paygate.health-cache.ttl-seconds", Integer.class)
                    .orElse(5);
            wrapped =
                new CachingLightningBackendWrapper(
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
    LightningBackend lightningBackend(
        PaygateProperties properties, tools.jackson.databind.json.JsonMapper objectMapper) {
      var lnbits = properties.getLnbits();
      int timeout =
          lnbits.getRequestTimeoutSeconds() != null
              ? lnbits.getRequestTimeoutSeconds()
              : properties.getLightning().getTimeoutSeconds();
      var config =
          lnbits.getConnectTimeoutSeconds() != null
              ? new com.greenharborlabs.paygate.lightning.lnbits.LnbitsConfig(
                  lnbits.getUrl(),
                  lnbits.getApiKey(),
                  timeout,
                  lnbits.getConnectTimeoutSeconds(),
                  lnbits.isAllowPlaintextHttp())
              : new com.greenharborlabs.paygate.lightning.lnbits.LnbitsConfig(
                  lnbits.getUrl(), lnbits.getApiKey(), timeout, 10, lnbits.isAllowPlaintextHttp());
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
    LndConfig lndConfig(
        PaygateProperties properties, org.springframework.core.env.Environment environment) {
      var lnd = properties.getLnd();
      if (lnd.isAllowPlaintext()) {
        DevelopmentSafetyPolicy.validatePlaintextLnd(environment, lnd.getHost());
      }
      int rpcDeadline =
          lnd.getRpcDeadlineSeconds() != null
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
          rpcDeadline);
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(io.grpc.ManagedChannel.class)
    io.grpc.ManagedChannel lndManagedChannel(LndConfig lndConfig) {
      return LndChannelFactory.create(lndConfig);
    }

    @Bean
    @ConditionalOnMissingBean(LightningBackend.class)
    LightningBackend lightningBackend(
        io.grpc.ManagedChannel lndManagedChannel, LndConfig lndConfig) {
      return new com.greenharborlabs.paygate.lightning.lnd.LndBackend(lndManagedChannel, lndConfig);
    }
  }
}
