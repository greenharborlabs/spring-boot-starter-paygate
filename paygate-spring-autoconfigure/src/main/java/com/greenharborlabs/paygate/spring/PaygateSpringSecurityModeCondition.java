package com.greenharborlabs.paygate.spring;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Condition that matches when the resolved L402 security mode is {@code spring-security}.
 *
 * <p>This matches when:
 *
 * <ul>
 *   <li>{@code paygate.security-mode=spring-security} (explicit), or
 *   <li>{@code paygate.security-mode=auto} (or unset) and Spring Security is on the classpath
 * </ul>
 *
 * <p>Used by {@code L402SecurityAutoConfiguration} in the spring-security module.
 */
public class PaygateSpringSecurityModeCondition implements Condition {

  @Override
  public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
    String resolved = PaygateSecurityModeResolver.resolve(context.getEnvironment());
    return PaygateSecurityModeResolver.MODE_SPRING_SECURITY.equals(resolved);
  }
}
