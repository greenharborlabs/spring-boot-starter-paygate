package com.greenharborlabs.paygate.spring.security;

import com.greenharborlabs.paygate.api.ChallengeContext;
import com.greenharborlabs.paygate.api.PaymentCredential;
import com.greenharborlabs.paygate.api.PaymentProtocol;
import com.greenharborlabs.paygate.api.PaymentReceipt;
import com.greenharborlabs.paygate.api.PaymentValidationException;
import com.greenharborlabs.paygate.core.macaroon.L402VerificationContext;
import com.greenharborlabs.paygate.core.macaroon.Macaroon;
import com.greenharborlabs.paygate.core.protocol.L402Credential;
import com.greenharborlabs.paygate.core.protocol.L402Exception;
import com.greenharborlabs.paygate.core.protocol.L402HeaderComponents;
import com.greenharborlabs.paygate.core.protocol.L402Validator;
import com.greenharborlabs.paygate.core.protocol.PriceValidationException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Spring Security {@link AuthenticationProvider} that validates payment credentials using either
 * the core {@link L402Validator} (for L402 tokens) or a matching {@link PaymentProtocol} (for
 * protocol-agnostic tokens such as MPP).
 *
 * <p>Accepts {@link PaygateAuthenticationToken} instances in their unauthenticated state and
 * returns an authenticated token with the validated credential, service name, and protocol-specific
 * attributes.
 */
public final class PaygateAuthenticationProvider implements AuthenticationProvider {

  private static final System.Logger log =
      System.getLogger(PaygateAuthenticationProvider.class.getName());

  private static final CapabilityResolver NOOP_RESOLVER = _ -> Set.of();
  private static final String CLIENT_IP_CAVEAT_KEY = "client_ip";

  private final L402Validator l402Validator;
  private final List<PaymentProtocol> protocols;
  private final String serviceName;
  private final CapabilityResolver capabilityResolver;
  private final boolean clientAddressBindingEnabled;

  public PaygateAuthenticationProvider(L402Validator l402Validator, String serviceName) {
    this(l402Validator, List.of(), serviceName, NOOP_RESOLVER, false);
  }

  public PaygateAuthenticationProvider(
      L402Validator l402Validator, List<PaymentProtocol> protocols, String serviceName) {
    this(l402Validator, protocols, serviceName, NOOP_RESOLVER, false);
  }

  public PaygateAuthenticationProvider(
      L402Validator l402Validator,
      List<PaymentProtocol> protocols,
      String serviceName,
      CapabilityResolver capabilityResolver) {
    this(l402Validator, protocols, serviceName, capabilityResolver, false);
  }

  /**
   * Creates a provider with optional enforcement that every L402 credential carries one client IP
   * caveat.
   *
   * @throws IllegalArgumentException if binding is enabled and the validator has no {@code
   *     client_ip} caveat verifier, because the caveat would otherwise be skipped as unknown
   */
  public PaygateAuthenticationProvider(
      L402Validator l402Validator,
      List<PaymentProtocol> protocols,
      String serviceName,
      CapabilityResolver capabilityResolver,
      boolean clientAddressBindingEnabled) {
    this.l402Validator = Objects.requireNonNull(l402Validator, "l402Validator must not be null");
    this.protocols = List.copyOf(Objects.requireNonNull(protocols, "protocols must not be null"));
    this.serviceName = serviceName;
    this.capabilityResolver = capabilityResolver != null ? capabilityResolver : NOOP_RESOLVER;
    if (clientAddressBindingEnabled && !l402Validator.hasCaveatVerifier(CLIENT_IP_CAVEAT_KEY)) {
      throw new IllegalArgumentException(
          "L402 client-address binding is enabled but the L402Validator has no "
              + "ClientIpCaveatVerifier registered for the client_ip caveat; add "
              + "ClientIpCaveatVerifier to the caveat verifiers or disable "
              + "paygate.protocols.l402.client-address-binding-enabled");
    }
    this.clientAddressBindingEnabled = clientAddressBindingEnabled;
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

  private Authentication authenticateL402(
      PaygateAuthenticationToken token, L402HeaderComponents components) {
    L402VerificationContext context =
        L402VerificationContext.builder()
            .serviceName(serviceName)
            .requestMetadata(token.getRequestMetadata())
            .currentTime(Instant.now())
            .build();

    try {
      L402Validator.ValidationResult result = l402Validator.validate(components, context);
      L402Credential credential = result.credential();
      try {
        if (clientAddressBindingEnabled && clientAddressCaveatCount(credential.macaroon()) != 1) {
          throw new L402Exception(
              com.greenharborlabs.paygate.core.protocol.ErrorCode.INVALID_MACAROON,
              "L402 credential validation failed",
              credential.tokenId());
        }
        return PaygateAuthenticationToken.authenticated(result, serviceName);
      } finally {
        credential.destroy();
      }
    } catch (PriceValidationException e) {
      PaymentValidationException.ErrorCode errorCode =
          e.isChallengeable()
              ? PaymentValidationException.ErrorCode.INSUFFICIENT
              : PaymentValidationException.ErrorCode.UNAVAILABLE;
      throw new BadCredentialsException(
          "L402 authentication failed",
          new PaymentValidationException(
              errorCode, "L402 paid-price validation failed", e.getTokenId(), e));
    } catch (L402Exception e) {
      throw new BadCredentialsException("L402 authentication failed", mapL402Failure(e));
    }
  }

  private static long clientAddressCaveatCount(Macaroon macaroon) {
    return macaroon.caveats().stream()
        .filter(caveat -> CLIENT_IP_CAVEAT_KEY.equals(caveat.key()))
        .count();
  }

  private static PaymentValidationException mapL402Failure(L402Exception failure) {
    PaymentValidationException.ErrorCode category =
        switch (failure.getErrorCode()) {
          case MALFORMED_HEADER -> PaymentValidationException.ErrorCode.MALFORMED;
          case LIGHTNING_UNAVAILABLE -> PaymentValidationException.ErrorCode.UNAVAILABLE;
          case INVALID_MACAROON,
              INVALID_PREIMAGE,
              EXPIRED_CREDENTIAL,
              INVALID_SERVICE,
              MISSING_REQUEST_CONTEXT,
              REVOKED_CREDENTIAL ->
              PaymentValidationException.ErrorCode.INVALID;
        };
    return new PaymentValidationException(
        category, "L402 credential validation failed", failure.getTokenId(), failure);
  }

  private Authentication authenticateProtocol(
      PaygateAuthenticationToken token, String authorizationHeader) {
    for (PaymentProtocol protocol : protocols) {
      if (protocol.canHandle(authorizationHeader)) {
        try {
          PaymentCredential credential = protocol.parseCredential(authorizationHeader);
          try {
            // Capabilities come from what the credential authenticates, never from the
            // capability declared by the endpoint the credential is presented to.
            Set<String> capabilities =
                new LinkedHashSet<>(
                    protocol.validateAndResolveCapabilities(
                        credential, token.getRequestMetadata()));
            capabilities.addAll(
                resolveCapabilitiesSafely(
                    new CapabilityResolutionContext(
                        credential.tokenId(), serviceName, null, token.getRequestMetadata())));
            PaymentReceipt receipt = createReceipt(protocol, credential, token.getReceiptRequest());
            return PaygateAuthenticationToken.authenticated(
                credential.tokenId(),
                serviceName,
                credential.sourceProtocolScheme(),
                // The credential's optional source is unauthenticated client input (no HMAC slot
                // covers it), so it is never exposed as an authentication attribute.
                Map.of(),
                paymentAuthorities(capabilities),
                receipt);
          } finally {
            // Authentication state contains only extracted facts, never the parsed credential.
            credential.close();
          }
        } catch (PaymentValidationException e) {
          throw new BadCredentialsException("Payment authentication failed", e);
        }
      }
    }

    throw new BadCredentialsException("No payment protocol found for authorization header");
  }

  private Set<String> resolveCapabilitiesSafely(CapabilityResolutionContext context) {
    try {
      return capabilityResolver.resolve(context);
    } catch (RuntimeException e) {
      log.log(
          System.Logger.Level.WARNING,
          "Capability resolution failed for token; proceeding without resolved capabilities",
          e);
      return Set.of();
    }
  }

  private static Set<GrantedAuthority> paymentAuthorities(Set<String> capabilities) {
    Set<GrantedAuthority> authorities = new LinkedHashSet<>();
    authorities.add(new SimpleGrantedAuthority("ROLE_PAYMENT"));
    for (String capability : capabilities) {
      if (capability != null) {
        authorities.add(new SimpleGrantedAuthority("PAYGATE_CAPABILITY_" + capability));
      }
    }
    return Set.copyOf(authorities);
  }

  private PaymentReceipt createReceipt(
      PaymentProtocol protocol,
      PaymentCredential credential,
      PaygateAuthenticationToken.ReceiptRequest receiptRequest) {
    if (receiptRequest == null) {
      return null;
    }
    try {
      ChallengeContext context =
          new ChallengeContext(
              credential.paymentHash(),
              credential.tokenId(),
              "",
              receiptRequest.priceSats(),
              receiptRequest.description(),
              serviceName,
              receiptRequest.timeoutSeconds(),
              receiptRequest.capability(),
              null,
              null,
              null);
      Optional<PaymentReceipt> receipt = protocol.createReceipt(credential, context);
      return receipt.orElse(null);
    } catch (RuntimeException e) {
      log.log(
          System.Logger.Level.DEBUG, "Receipt creation failed for protocol {0}", protocol.scheme());
      return null;
    }
  }

  @Override
  public boolean supports(Class<?> authentication) {
    return PaygateAuthenticationToken.class.isAssignableFrom(authentication);
  }
}
