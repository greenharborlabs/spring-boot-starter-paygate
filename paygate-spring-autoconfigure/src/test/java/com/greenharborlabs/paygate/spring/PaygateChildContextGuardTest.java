package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

/**
 * Regression tests for SR-2026-10-18-16: {@code @PaymentRequired} handlers in a child context, such
 * as an additional {@code DispatcherServlet}'s, are never scanned by the root registry. The root
 * context's guard must fail the child's refresh instead of letting it serve paid routes unpaid.
 */
@DisplayName("Child application context @PaymentRequired guard")
class PaygateChildContextGuardTest {

  private final WebApplicationContextRunner rootRunner =
      new WebApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(PaygateAutoConfiguration.class))
          .withPropertyValues(
              "paygate.enabled=true", "paygate.backend=lnbits", "paygate.root-key-store=memory")
          .withBean(LightningBackend.class, () -> mock(LightningBackend.class));

  @Test
  @DisplayName("a root-only application with paid handlers still starts")
  void rootOnlyApplicationStarts() {
    rootRunner
        .withUserConfiguration(PaidController.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(PaygateChildContextGuard.class);
            });
  }

  @Test
  @DisplayName("a child context declaring @PaymentRequired fails to refresh")
  void childContextWithPaidHandlerFailsToRefresh() {
    rootRunner.run(
        root -> {
          var child = childOf(root.getSourceApplicationContext(), PaidController.class);

          assertThatThrownBy(child::refresh)
              .isInstanceOf(IllegalStateException.class)
              .hasMessageContaining(
                  "@PaymentRequired is not supported in child application context")
              .hasMessageContaining("paygateChildContextGuardTest.PaidController");
          assertThat(child.isActive()).isFalse();
        });
  }

  @Test
  @DisplayName("a child context declaring @PaymentRequired on an interface method fails")
  void childContextWithInterfacePaidHandlerFails() {
    rootRunner.run(
        root -> {
          var child = childOf(root.getSourceApplicationContext(), InterfacePaidController.class);

          assertThatThrownBy(child::refresh).isInstanceOf(IllegalStateException.class);
        });
  }

  @Test
  @DisplayName("a child context declaring @PaymentRequired behind a CGLIB proxy fails")
  void childContextWithClassProxiedPaidHandlerFails() {
    rootRunner.run(
        root -> {
          var child = childOf(root.getSourceApplicationContext(), PaidController.class);
          child.addBeanFactoryPostProcessor(
              beanFactory -> beanFactory.addBeanPostProcessor(proxyingPostProcessor(true)));

          assertThatThrownBy(child::refresh).isInstanceOf(IllegalStateException.class);
        });
  }

  @Test
  @DisplayName("a child context declaring @PaymentRequired behind a JDK proxy fails")
  void childContextWithInterfaceProxiedPaidHandlerFails() {
    rootRunner.run(
        root -> {
          var child = childOf(root.getSourceApplicationContext(), InterfacePaidController.class);
          child.addBeanFactoryPostProcessor(
              beanFactory -> beanFactory.addBeanPostProcessor(proxyingPostProcessor(false)));

          assertThatThrownBy(child::refresh).isInstanceOf(IllegalStateException.class);
        });
  }

  @Test
  @DisplayName("a child context without paid handlers refreshes normally")
  void childContextWithoutPaidHandlersRefreshes() {
    rootRunner
        .withUserConfiguration(PaidController.class)
        .run(
            root -> {
              var child = childOf(root.getSourceApplicationContext(), FreeController.class);

              assertThatCode(child::refresh).doesNotThrowAnyException();
              child.close();
            });
  }

  @Test
  @DisplayName("only the child's own beans are inspected, not its ancestors'")
  void inspectsOnlyTheChildsOwnBeans() {
    rootRunner
        .withUserConfiguration(PaidController.class)
        .run(
            root -> {
              var child = childOf(root.getSourceApplicationContext(), FreeController.class);
              child.refresh();

              assertThat(PaygateChildContextGuard.findPaidBeans(child)).isEmpty();
              assertThat(PaygateChildContextGuard.findPaidBeans(root.getSourceApplicationContext()))
                  .hasSize(1);
              child.close();
            });
  }

  private static AnnotationConfigWebApplicationContext childOf(
      ApplicationContext parent, Class<?> component) {
    var child = new AnnotationConfigWebApplicationContext();
    child.setParent(parent);
    child.register(component);
    return child;
  }

  private static BeanPostProcessor proxyingPostProcessor(boolean proxyTargetClass) {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof PaidController) && !(bean instanceof InterfacePaidController)) {
          return bean;
        }
        var factory = new ProxyFactory(bean);
        factory.setProxyTargetClass(proxyTargetClass);
        return factory.getProxy();
      }
    };
  }

  @RestController
  static class PaidController {
    @PaymentRequired(priceSats = 10)
    @GetMapping("/child/paid")
    public String paid() {
      return "PAID";
    }
  }

  interface PaidApi {
    @PaymentRequired(priceSats = 10)
    @GetMapping("/child/iface")
    String paid();
  }

  @RestController
  static class InterfacePaidController implements PaidApi {
    @Override
    public String paid() {
      return "PAID";
    }
  }

  @RestController
  static class FreeController {
    @GetMapping("/child/free")
    public String free() {
      return "FREE";
    }
  }
}
