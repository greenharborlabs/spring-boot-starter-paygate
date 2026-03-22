package com.greenharborlabs.paygate.spring.security;

import com.greenharborlabs.paygate.core.macaroon.L402VerificationContext;
import com.greenharborlabs.paygate.core.protocol.L402Exception;
import com.greenharborlabs.paygate.core.protocol.L402HeaderComponents;
import com.greenharborlabs.paygate.core.protocol.L402Validator;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;

import java.time.Instant;
import java.util.Objects;

/**
 * Spring Security {@link AuthenticationProvider} that validates L402 credentials
 * using the core {@link L402Validator}.
 *
 * <p>Accepts {@link PaygateAuthenticationToken} instances in their unauthenticated state
 * (holding raw macaroon + preimage strings), delegates validation to {@link L402Validator},
 * and returns an authenticated token with the validated credential, service name, and
 * caveat-derived attributes.
 */
public final class PaygateAuthenticationProvider implements AuthenticationProvider {

    private final L402Validator l402Validator;
    private final String serviceName;

    public PaygateAuthenticationProvider(L402Validator l402Validator, String serviceName) {
        this.l402Validator = Objects.requireNonNull(l402Validator, "l402Validator must not be null");
        this.serviceName = serviceName;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        if (!(authentication instanceof PaygateAuthenticationToken token)) {
            return null;
        }

        L402HeaderComponents components = token.getComponents();
        if (components == null) {
            throw new BadCredentialsException("L402 token missing credentials");
        }

        L402VerificationContext context = L402VerificationContext.builder()
                .serviceName(serviceName)
                .requestedCapability(token.getRequestedCapability())
                .requestMetadata(token.getRequestMetadata())
                .currentTime(Instant.now())
                .build();

        try {
            L402Validator.ValidationResult result = l402Validator.validate(components, context);
            return PaygateAuthenticationToken.authenticated(result.credential(), serviceName);
        } catch (L402Exception e) {
            throw new BadCredentialsException("L402 authentication failed", e);
        }
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return PaygateAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
