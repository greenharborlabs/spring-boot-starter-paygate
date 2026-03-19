package com.greenharborlabs.l402.spring.security;

import com.greenharborlabs.l402.core.macaroon.L402VerificationContext;
import com.greenharborlabs.l402.core.protocol.L402Exception;
import com.greenharborlabs.l402.core.protocol.L402HeaderComponents;
import com.greenharborlabs.l402.core.protocol.L402Validator;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;

import java.util.Objects;

/**
 * Spring Security {@link AuthenticationProvider} that validates L402 credentials
 * using the core {@link L402Validator}.
 *
 * <p>Accepts {@link L402AuthenticationToken} instances in their unauthenticated state
 * (holding raw macaroon + preimage strings), delegates validation to {@link L402Validator},
 * and returns an authenticated token with the validated credential, service name, and
 * caveat-derived attributes.
 */
public final class L402AuthenticationProvider implements AuthenticationProvider {

    private final L402Validator l402Validator;
    private final String serviceName;

    public L402AuthenticationProvider(L402Validator l402Validator, String serviceName) {
        this.l402Validator = Objects.requireNonNull(l402Validator, "l402Validator must not be null");
        this.serviceName = serviceName;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        if (!(authentication instanceof L402AuthenticationToken token)) {
            return null;
        }

        L402HeaderComponents components = token.getComponents();
        if (components == null) {
            throw new BadCredentialsException("L402 token missing credentials");
        }

        L402VerificationContext context = L402VerificationContext.builder()
                .serviceName(serviceName)
                .requestedCapability(token.getRequestedCapability())
                .build();

        try {
            L402Validator.ValidationResult result = l402Validator.validate(components, context);
            return L402AuthenticationToken.authenticated(result.credential(), serviceName);
        } catch (L402Exception e) {
            throw new BadCredentialsException("L402 authentication failed", e);
        }
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return L402AuthenticationToken.class.isAssignableFrom(authentication);
    }
}
