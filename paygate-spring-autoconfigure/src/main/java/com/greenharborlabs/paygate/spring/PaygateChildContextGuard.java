package com.greenharborlabs.paygate.spring;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.aop.framework.autoproxy.AutoProxyUtils;
import org.springframework.aop.scope.ScopedProxyUtils;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.util.ClassUtils;

/**
 * Fails closed when a descendant application context declares {@link PaymentRequired} handlers.
 *
 * <p>Paygate discovers and enforces paid handlers only in the context where it is auto-configured.
 * A child context, such as the context of an additional {@code DispatcherServlet}, has its own
 * handler mappings that the endpoint registry never scans. A child context propagates its {@link
 * ContextRefreshedEvent} to its parent, so this listener inspects the child's own beans and throws
 * if any declares {@code @PaymentRequired}. The child's refresh then fails, and its servlet fails
 * to initialise instead of serving a paid route without payment.
 */
final class PaygateChildContextGuard implements ApplicationListener<ContextRefreshedEvent> {

  private static final System.Logger log =
      System.getLogger(PaygateChildContextGuard.class.getName());

  private final ApplicationContext rootContext;

  PaygateChildContextGuard(ApplicationContext rootContext) {
    this.rootContext = Objects.requireNonNull(rootContext, "rootContext must not be null");
  }

  @Override
  public void onApplicationEvent(ContextRefreshedEvent event) {
    ApplicationContext refreshed = event.getApplicationContext();
    if (refreshed == rootContext) {
      return;
    }
    List<String> paidBeans = findPaidBeans(refreshed);
    if (!paidBeans.isEmpty()) {
      throw new IllegalStateException(
          "@PaymentRequired is not supported in child application context '"
              + refreshed.getId()
              + "' (for example, an additional DispatcherServlet). Paygate cannot discover or"
              + " enforce its paid handlers; declare them in the root application context."
              + " Beans declaring @PaymentRequired: "
              + paidBeans);
    }
  }

  /**
   * Returns the names of the context's own beans whose class declares {@code @PaymentRequired}.
   *
   * <p>Candidate detection mirrors Spring MVC's {@code AbstractHandlerMethodMapping} without
   * ancestor detection: this context's own bean names, excluding scoped-proxy targets, skipping any
   * bean whose type is unknown or cannot be resolved. A bean Spring MVC cannot type cannot become a
   * handler, so healthy child contexts with lazy or unresolvable beans, such as Spring Cloud {@code
   * NamedContextFactory} client contexts, are not failed.
   */
  static List<String> findPaidBeans(ApplicationContext context) {
    ConfigurableListableBeanFactory beanFactory =
        context instanceof ConfigurableApplicationContext configurable
            ? configurable.getBeanFactory()
            : null;
    var paidBeans = new ArrayList<String>();
    // getBeanNamesForType does not consult ancestor contexts.
    for (String beanName : context.getBeanNamesForType(Object.class)) {
      if (ScopedProxyUtils.isScopedTarget(beanName)) {
        continue;
      }
      Class<?> beanClass = targetClass(context, beanFactory, beanName);
      if (beanClass != null && declaresPaymentRequired(beanClass)) {
        paidBeans.add(beanName);
      }
    }
    return paidBeans;
  }

  private static @Nullable Class<?> targetClass(
      ApplicationContext context,
      @Nullable ConfigurableListableBeanFactory beanFactory,
      String beanName) {
    Class<?> type;
    try {
      type =
          beanFactory != null
              ? AutoProxyUtils.determineTargetClass(beanFactory, beanName)
              : context.getType(beanName);
    } catch (Throwable ex) {
      // An unresolvable bean type, probably from a lazy bean; Spring MVC ignores it the same way.
      log.log(
          System.Logger.Level.DEBUG,
          "Could not resolve type for bean {0}; skipping @PaymentRequired check: {1}",
          beanName,
          ex.getClass().getName());
      return null;
    }
    return type == null ? null : ClassUtils.getUserClass(type);
  }

  static boolean declaresPaymentRequired(Class<?> type) {
    if (MergedAnnotations.from(type, SearchStrategy.TYPE_HIERARCHY)
        .isPresent(PaymentRequired.class)) {
      return true;
    }
    // Includes methods declared on superclasses and interfaces, and JDK proxy interfaces.
    return !MethodIntrospector.selectMethods(
            type,
            (MethodIntrospector.MetadataLookup<Boolean>)
                PaygateChildContextGuard::paymentRequiredOrNull)
        .isEmpty();
  }

  private static @Nullable Boolean paymentRequiredOrNull(Method method) {
    return AnnotatedElementUtils.hasAnnotation(method, PaymentRequired.class) ? Boolean.TRUE : null;
  }
}
