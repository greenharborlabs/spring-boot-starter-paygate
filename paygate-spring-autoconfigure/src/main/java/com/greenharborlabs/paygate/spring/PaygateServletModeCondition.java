package com.greenharborlabs.paygate.spring;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Condition that matches when the resolved L402 security mode is {@code servlet}.
 *
 * <p>Applied to the {@code FilterRegistrationBean} for {@link PaygateSecurityFilter} so that the
 * servlet filter is only registered when servlet mode is active.
 */
class PaygateServletModeCondition implements Condition {

  @Override
  public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
    String resolved = PaygateSecurityModeResolver.resolve(context.getEnvironment());
    return PaygateSecurityModeResolver.MODE_SERVLET.equals(resolved);
  }
}
