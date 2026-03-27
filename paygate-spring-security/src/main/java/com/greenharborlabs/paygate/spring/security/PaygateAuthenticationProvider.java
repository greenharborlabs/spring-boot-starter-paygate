package com.greenharborlabs.paygate.spring.security;

import com.greenharborlabs.paygate.api.PaymentCredential;
import com.greenharborlabs.paygate.api.PaymentProtocol;
import com.greenharborlabs.paygate.api.PaymentValidationException;
import com.greenharborlabs.paygate.core.macaroon.L402VerificationContext;
import com.greenharborlabs.paygate.core.protocol.L402Exception;
import com.greenharborlabs.paygate.core.protocol.L402HeaderComponents;
import com.greenharborlabs.paygate.core.protocol.L402Validator;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Spring Security {@link AuthenticationProvider} that validates payment credentials
 * using either the core {@link L402Validator} (for L402 tokens) or a matching
 * {@link PaymentProtocol} (for protocol-agnostic tokens such as MPP).
 *
 * <p>Accepts {@link PaygateAuthenticationToken} instances in their unauthenticated state
 * and returns an authenticated token with the validated credential, service name, and
 * protocol-specific attributes.
 */
public final class PaygateAuthenticationProvider implements AuthenticationProvider {

    private final L402Validator l402Validator;
    private final List<PaymentProtocol> protocols;
    private final String serviceName;

    public PaygateAuthenticationProvider(L402Validator l402Validator, String serviceName) {
        this(l402Validator, List.of(), serviceName);
    }

    public PaygateAuthenticationProvider(L402Validator l402Validator,
                                         List<PaymentProtocol> protocols,
                                         String serviceName) {
        this.l402Validator = Objects.requireNonNull(l402Validator, "l402Validator must not be null");
        this.protocols = List.copyOf(
                Objects.requireNonNull(protocols, "protocols must not be null"));
        this.serviceName = serviceName;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        if (!(authentication instanceof PaygateAuthenticationToken token)) {
            return null;
        }

        L402HeaderComponents components = token.getComponents();
        if (components != null) {
            return authenticateL402(token, components);
        }

        String authorizationHeader = token.getAuthorizationHeader();
        if (authorizationHeader != null) {
            return authenticateProtocol(token, authorizationHeader);
        }

        throw new BadCredentialsException("Payment token missing credentials");
    }

    private Authentication authenticateL402(PaygateAuthenticationToken token,
                                            L402HeaderComponents components) {
        L402VerificationContext context = L402VerificationContext.builder()
                .serviceName(serviceName)
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

    private Authentication authenticateProtocol(PaygateAuthenticationToken token,
                                                String authorizationHeader) {
        for (PaymentProtocol protocol : protocols) {
            if (protocol.canHandle(authorizationHeader)) {
                try {
                    PaymentCredential credential = protocol.parseCredential(authorizationHeader);
                    protocol.validate(credential, token.getRequestMetadata());
                    return PaygateAuthenticationToken.authenticated(credential, serviceName);
                } catch (PaymentValidationException e) {
                    throw new BadCredentialsException("Payment authentication failed", e);
                }
            }
        }

        throw new BadCredentialsException("No payment protocol found for authorization header");
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return PaygateAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
