package com.greenharborlabs.paygate.spring;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.aop.framework.autoproxy.AutoProxyUtils;
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

  /** Returns the names of the context's own beans whose class declares {@code @PaymentRequired}. */
  static List<String> findPaidBeans(ApplicationContext context) {
    ConfigurableListableBeanFactory beanFactory =
        context instanceof ConfigurableApplicationContext configurable
            ? configurable.getBeanFactory()
            : null;
    // Bean definition and singleton names are local to this context; ancestors are not consulted.
    var beanNames = new LinkedHashSet<>(Arrays.asList(context.getBeanDefinitionNames()));
    if (beanFactory != null) {
      beanNames.addAll(Arrays.asList(beanFactory.getSingletonNames()));
    }
    var paidBeans = new ArrayList<String>();
    for (String beanName : beanNames) {
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
    Class<?> type =
        beanFactory != null
            ? AutoProxyUtils.determineTargetClass(beanFactory, beanName)
            : context.getType(beanName, false);
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
