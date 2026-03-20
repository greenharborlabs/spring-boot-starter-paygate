package com.greenharborlabs.l402.spring;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Condition that matches when the resolved L402 security mode is {@code spring-security}.
 *
 * <p>This matches when:
 * <ul>
 *   <li>{@code l402.security-mode=spring-security} (explicit), or</li>
 *   <li>{@code l402.security-mode=auto} (or unset) and Spring Security is on the classpath</li>
 * </ul>
 *
 * <p>Used by {@code L402SecurityAutoConfiguration} in the spring-security module.
 */
public class L402SpringSecurityModeCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String resolved = L402SecurityModeResolver.resolve(context.getEnvironment());
        return L402SecurityModeResolver.MODE_SPRING_SECURITY.equals(resolved);
    }
}
