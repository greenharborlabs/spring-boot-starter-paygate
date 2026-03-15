package com.greenharborlabs.l402.spring;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Condition that matches when the resolved L402 security mode is {@code servlet}.
 *
 * <p>Applied to the {@code FilterRegistrationBean} for {@link L402SecurityFilter}
 * so that the servlet filter is only registered when servlet mode is active.
 */
class L402ServletModeCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String resolved = L402SecurityModeResolver.resolve(context.getEnvironment());
        return L402SecurityModeResolver.MODE_SERVLET.equals(resolved);
    }
}
