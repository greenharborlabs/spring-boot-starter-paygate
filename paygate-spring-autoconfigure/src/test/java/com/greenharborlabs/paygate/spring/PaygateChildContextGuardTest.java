package com.greenharborlabs.paygate.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.greenharborlabs.paygate.core.lightning.LightningBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.annotation.RequestScope;
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

  @Test
  @DisplayName(
      "a child context with an uninitialisable lazy factory bean and no paid handlers refreshes")
  void childContextWithUninitialisableLazyFactoryBeanRefreshes() {
    rootRunner.run(
        root -> {
          var child = childOf(root.getSourceApplicationContext(), FreeController.class);
          child.addBeanFactoryPostProcessor(
              beanFactory -> {
                var definition = new GenericBeanDefinition();
                definition.setBeanClass(FailingFactoryBean.class);
                definition.setLazyInit(true);
                ((BeanDefinitionRegistry) beanFactory)
                    .registerBeanDefinition("unresolvableClient", definition);
              });

          assertThatCode(child::refresh).doesNotThrowAnyException();
          assertThat(PaygateChildContextGuard.findPaidBeans(child)).isEmpty();
          child.close();
        });
  }

  @Test
  @DisplayName("a child context with a lazy factory bean of unknown type refreshes")
  void childContextWithLazyUnknownTypeFactoryBeanRefreshes() {
    rootRunner.run(
        root -> {
          var child = childOf(root.getSourceApplicationContext(), FreeController.class);
          child.addBeanFactoryPostProcessor(
              beanFactory -> {
                var definition = new GenericBeanDefinition();
                definition.setBeanClass(UnknownTypeFactoryBean.class);
                definition.setLazyInit(true);
                ((BeanDefinitionRegistry) beanFactory)
                    .registerBeanDefinition("lazyClientFactory", definition);
              });

          assertThatCode(child::refresh).doesNotThrowAnyException();
          assertThat(PaygateChildContextGuard.findPaidBeans(child)).isEmpty();
          child.close();
        });
  }

  @Test
  @DisplayName("beans whose type is null or cannot be resolved are skipped, paid beans still found")
  void unresolvableAndUnknownTypesAreSkipped() {
    var context = mock(ApplicationContext.class);
    when(context.getBeanNamesForType(Object.class))
        .thenReturn(new String[] {"broken", "unknown", "paid"});
    when(context.getType("broken"))
        .thenThrow(new BeanCreationException("broken", "type cannot be resolved"));
    when(context.getType("unknown")).thenReturn(null);
    doReturn(PaidController.class).when(context).getType("paid");

    assertThat(PaygateChildContextGuard.findPaidBeans(context)).containsExactly("paid");
  }

  @Test
  @DisplayName("a scoped-proxy paid bean fails once, through its proxy and not its target")
  void scopedProxyPaidBeanIsDetectedThroughItsProxy() {
    rootRunner.run(
        root -> {
          var child =
              childOf(root.getSourceApplicationContext(), RequestScopedPaidController.class);

          assertThatThrownBy(child::refresh)
              .isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("[paygateChildContextGuardTest.RequestScopedPaidController]");
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
  @RequestScope
  static class RequestScopedPaidController {
    @PaymentRequired(priceSats = 10)
    @GetMapping("/child/scoped")
    public String paid() {
      return "PAID";
    }
  }

  /** A client-style factory bean whose product type is unknown until it is initialised. */
  @SuppressWarnings("rawtypes")
  static class UnknownTypeFactoryBean implements FactoryBean {
    @Override
    public Object getObject() {
      throw new IllegalStateException("client is not available");
    }

    @Override
    public Class<?> getObjectType() {
      return null;
    }
  }

  /** A raw factory bean whose type is unknowable without initialising it, which always fails. */
  @SuppressWarnings("rawtypes")
  static class FailingFactoryBean implements FactoryBean {
    FailingFactoryBean() {
      throw new IllegalStateException("client configuration is not available");
    }

    @Override
    public Object getObject() {
      throw new IllegalStateException("unreachable");
    }

    @Override
    public Class<?> getObjectType() {
      return null;
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
