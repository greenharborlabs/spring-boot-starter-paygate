package com.greenharborlabs.paygate.spring.security;

import com.greenharborlabs.paygate.api.PaymentCredential;
import com.greenharborlabs.paygate.api.PaymentReceipt;
import com.greenharborlabs.paygate.core.protocol.L402Credential;
import com.greenharborlabs.paygate.core.protocol.L402HeaderComponents;
import com.greenharborlabs.paygate.core.protocol.L402Validator;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Spring Security authentication token for payment credentials (L402 and MPP).
 *
 * <p>Before authentication: holds raw credential data from the Authorization header. After
 * authentication: holds a validated credential with tokenId, service name, and protocol-specific
 * attributes accessible via SpEL in {@code @PreAuthorize} expressions.
 *
 * <p>Two unauthenticated paths exist:
 *
 * <ul>
 *   <li>L402 path: created from {@link L402HeaderComponents} (parsed macaroon + preimage)
 *   <li>Protocol-agnostic path: created from a raw Authorization header string
 * </ul>
 *
 * <p>Two authenticated paths exist:
 *
 * <ul>
 *   <li>L402-only: created from a validator-produced {@link L402Validator.ValidationResult}
 *   <li>Protocol-agnostic: created via {@link #authenticated(PaymentCredential, String)}
 * </ul>
 */
public final class PaygateAuthenticationToken extends AbstractAuthenticationToken {

  // --- L402-specific unauthenticated fields ---
  private final L402HeaderComponents components;

  // --- Protocol-agnostic unauthenticated field ---
  private final String authorizationHeader;

  // Authenticated tokens deliberately retain no credential object. A credential can expose a
  // macaroon, preimage, or another usable payment secret transitively.
  // A receipt is response-only data. It is deliberately transient so a serialized SecurityContext
  // cannot retain it beyond the current request.
  private final transient PaymentReceipt receipt;
  private final ReceiptRequest receiptRequest;
  private final String protocolScheme;

  // --- Common fields ---
  private final String tokenId;
  private final String serviceName;
  private final Map<String, String> attributes;
  private final Map<String, String> requestMetadata;

  // ========== L402 Unauthenticated Constructors (preserved) ==========

  /** Creates an unauthenticated token from parsed L402 header components. */
  public PaygateAuthenticationToken(L402HeaderComponents components) {
    this(components, Collections.emptyMap());
  }

  /**
   * Creates an unauthenticated token from parsed header components with request metadata for
   * delegation caveat verification.
   *
   * @param components parsed L402 header components, must not be null
   * @param requestMetadata request metadata (path, method, client IP, capability), must not be null
   */
  public PaygateAuthenticationToken(
      L402HeaderComponents components, Map<String, String> requestMetadata) {
    super(Collections.emptyList());
    this.components = Objects.requireNonNull(components, "components must not be null");
    this.authorizationHeader = null;
    this.receipt = null;
    this.receiptRequest = null;
    this.protocolScheme = null;
    this.tokenId = null;
    this.serviceName = null;
    this.attributes = Collections.emptyMap();
    this.requestMetadata =
        Map.copyOf(Objects.requireNonNull(requestMetadata, "requestMetadata must not be null"));
    setAuthenticated(false);
  }

  // ========== Protocol-Agnostic Unauthenticated Constructor ==========

  /**
   * Creates an unauthenticated token from a raw Authorization header for protocol-agnostic
   * authentication. Used when the filter detects a non-L402 credential (e.g., MPP).
   *
   * @param authorizationHeader raw Authorization header value, must not be null
   * @param requestMetadata request metadata (path, method, client IP, capability), must not be null
   * @return unauthenticated token carrying the raw header
   */
  public static PaygateAuthenticationToken unauthenticated(
      String authorizationHeader, Map<String, String> requestMetadata) {
    return new PaygateAuthenticationToken(
        Objects.requireNonNull(authorizationHeader, "authorizationHeader must not be null"),
        Objects.requireNonNull(requestMetadata, "requestMetadata must not be null"),
        null,
        null);
  }

  static PaygateAuthenticationToken unauthenticated(
      String authorizationHeader,
      Map<String, String> requestMetadata,
      ReceiptRequest receiptRequest) {
    return new PaygateAuthenticationToken(
        Objects.requireNonNull(authorizationHeader, "authorizationHeader must not be null"),
        Objects.requireNonNull(requestMetadata, "requestMetadata must not be null"),
        null,
        receiptRequest);
  }

  /**
   * Private constructor for protocol-agnostic unauthenticated tokens. The unused marker parameter
   * disambiguates from L402 constructors.
   */
  private PaygateAuthenticationToken(
      String authorizationHeader,
      Map<String, String> requestMetadata,
      Void marker,
      ReceiptRequest receiptRequest) {
    super(Collections.emptyList());
    this.components = null;
    this.authorizationHeader = authorizationHeader;
    this.receipt = null;
    this.receiptRequest = receiptRequest;
    this.protocolScheme = null;
    this.tokenId = null;
    this.serviceName = null;
    this.attributes = Collections.emptyMap();
    this.requestMetadata = Map.copyOf(requestMetadata);
    setAuthenticated(false);
  }

  // ========== Authenticated Constructor ==========

  /** Private constructor for credential-free authenticated tokens. */
  private PaygateAuthenticationToken(
      String tokenId,
      String serviceName,
      String protocolScheme,
      Collection<? extends GrantedAuthority> authorities,
      Map<String, String> attributes,
      PaymentReceipt receipt) {
    super(List.copyOf(authorities));
    this.components = null;
    this.authorizationHeader = null;
    this.receipt = receipt;
    this.receiptRequest = null;
    this.protocolScheme = Objects.requireNonNull(protocolScheme, "protocolScheme must not be null");
    this.tokenId = Objects.requireNonNull(tokenId, "tokenId must not be null");
    this.serviceName = serviceName;
    this.attributes = Map.copyOf(attributes);
    this.requestMetadata = Collections.emptyMap();
    super.setAuthenticated(true);
  }

  // ========== Static Factories ==========

  /**
   * Creates an authenticated L402 token from verifier-approved validation output.
   *
   * <p>This is the preferred L402 factory. It exposes only attributes returned by successful
   * registered caveat verifiers and derives authorities only from the validator's effective
   * capabilities. It never promotes merely parsed credential caveats into Spring Security
   * authorization state.
   *
   * @param validationResult validated credential, verified attributes, and capabilities
   * @param serviceName verified service name, may be null
   * @return authenticated token containing only trusted identity facts
   */
  public static PaygateAuthenticationToken authenticated(
      L402Validator.ValidationResult validationResult, String serviceName) {
    Objects.requireNonNull(validationResult, "validationResult must not be null");
    L402Credential credential = validationResult.credential();
    return authenticated(
        credential.tokenId(),
        serviceName,
        "L402",
        validationResult.verifiedAttributes(),
        l402Authorities(validationResult.effectiveCapabilities()));
  }

  /**
   * Creates an authenticated token from a validated L402 credential with no caveat-derived
   * attributes. Delegates to the capability overload with an empty set.
   *
   * <p><strong>Security migration:</strong> this method formerly copied raw parsed caveats into
   * authorization attributes, even though a credential alone cannot establish verifier provenance.
   * It now retains only protected system metadata. Migrate to {@link
   * #authenticated(L402Validator.ValidationResult, String)} to expose verifier-approved facts.
   *
   * @deprecated use {@link #authenticated(L402Validator.ValidationResult, String)} with the
   *     validator result that established attribute provenance
   */
  @Deprecated(since = "0.1.6", forRemoval = false)
  public static PaygateAuthenticationToken authenticated(
      L402Credential credential, String serviceName) {
    return authenticated(credential, serviceName, Set.of());
  }

  /**
   * Creates an authenticated token from a validated L402 credential with protected system metadata
   * and explicit capability authorities.
   *
   * <p><strong>Security migration:</strong> this method no longer copies raw parsed caveats into
   * authorization attributes because it cannot know which caveats were verifier-approved. Migrate
   * to {@link #authenticated(L402Validator.ValidationResult, String)} to retain approved facts.
   *
   * @param credential validated L402 credential, must not be null
   * @param serviceName service name, may be null
   * @param capabilities explicit capabilities to grant as {@code PAYGATE_CAPABILITY_*} authorities,
   *     must not be null
   * @return authenticated token without caveat-derived attributes
   * @deprecated use {@link #authenticated(L402Validator.ValidationResult, String)} with the
   *     validator result that established attribute provenance
   */
  @Deprecated(since = "0.1.6", forRemoval = false)
  public static PaygateAuthenticationToken authenticated(
      L402Credential credential, String serviceName, Set<String> capabilities) {
    Objects.requireNonNull(credential, "credential must not be null");
    Objects.requireNonNull(capabilities, "capabilities must not be null");
    return authenticated(
        credential.tokenId(), serviceName, "L402", Map.of(), l402Authorities(capabilities));
  }

  private static Set<GrantedAuthority> l402Authorities(Set<String> capabilities) {
    Set<GrantedAuthority> authorities = new LinkedHashSet<>();
    authorities.add(new SimpleGrantedAuthority("ROLE_PAYMENT"));
    authorities.add(new SimpleGrantedAuthority("ROLE_L402"));
    for (String capability : capabilities) {
      if (capability != null) {
        authorities.add(new SimpleGrantedAuthority("L402_CAPABILITY_" + capability));
        authorities.add(new SimpleGrantedAuthority("PAYGATE_CAPABILITY_" + capability));
      }
    }
    return authorities;
  }

  /**
   * Creates an authenticated token from a validated protocol-agnostic payment credential. Delegates
   * to the 3-arg overload with an empty capabilities set.
   *
   * @param paymentCredential validated payment credential, must not be null
   * @param serviceName service name, may be null
   * @return authenticated token
   */
  public static PaygateAuthenticationToken authenticated(
      PaymentCredential paymentCredential, String serviceName) {
    return authenticated(paymentCredential, serviceName, Set.of());
  }

  /**
   * Creates an authenticated token from a validated protocol-agnostic payment credential, adding
   * {@code PAYGATE_CAPABILITY_*} authorities from the explicit capabilities set.
   *
   * <p>For L402 credentials, grants {@code ROLE_L402} and {@code ROLE_PAYMENT} authorities. For MPP
   * credentials, grants {@code ROLE_PAYMENT} authority with simpler attributes.
   *
   * @param paymentCredential validated payment credential, must not be null
   * @param serviceName service name, may be null
   * @param capabilities explicit capabilities to grant as {@code PAYGATE_CAPABILITY_*} authorities,
   *     must not be null
   * @return authenticated token
   */
  public static PaygateAuthenticationToken authenticated(
      PaymentCredential paymentCredential, String serviceName, Set<String> capabilities) {
    Objects.requireNonNull(paymentCredential, "paymentCredential must not be null");
    Objects.requireNonNull(capabilities, "capabilities must not be null");

    Map<String, String> attrs = new HashMap<>();
    attrs.put("tokenId", paymentCredential.tokenId());
    attrs.put("protocolScheme", paymentCredential.sourceProtocolScheme());
    if (paymentCredential.source() != null) {
      attrs.put("source", paymentCredential.source());
    }
    if (serviceName != null) {
      attrs.put("serviceName", serviceName);
    }

    Set<GrantedAuthority> authorities = new LinkedHashSet<>();
    authorities.add(new SimpleGrantedAuthority("ROLE_PAYMENT"));

    if ("L402".equals(paymentCredential.sourceProtocolScheme())) {
      authorities.add(new SimpleGrantedAuthority("ROLE_L402"));
    }

    for (String cap : capabilities) {
      if (cap != null) {
        authorities.add(new SimpleGrantedAuthority("PAYGATE_CAPABILITY_" + cap));
      }
    }

    return authenticated(
        paymentCredential.tokenId(),
        serviceName,
        paymentCredential.sourceProtocolScheme(),
        attrs,
        authorities);
  }

  /**
   * Creates a credential-free authenticated token from facts approved by the authentication
   * provider. This is the preferred factory for providers: callers must pass only values whose
   * provenance has been verified, never parsed credential data.
   *
   * @param tokenId verified opaque token identifier
   * @param serviceName verified service name, may be null
   * @param protocolScheme verified protocol scheme
   * @param trustedAttributes approved, non-secret authentication facts
   * @param authorities granted authorities
   * @return a credential-free authenticated token
   */
  public static PaygateAuthenticationToken authenticated(
      String tokenId,
      String serviceName,
      String protocolScheme,
      Map<String, String> trustedAttributes,
      Collection<? extends GrantedAuthority> authorities) {
    return authenticated(
        tokenId, serviceName, protocolScheme, trustedAttributes, authorities, null);
  }

  /**
   * Creates a credential-free token with a response-only receipt for the current request.
   *
   * <p>This package-private handoff lets the provider avoid retaining a {@link PaymentCredential}
   * solely for response writing. The receipt is transient and never becomes SecurityContext state.
   */
  static PaygateAuthenticationToken authenticated(
      String tokenId,
      String serviceName,
      String protocolScheme,
      Map<String, String> trustedAttributes,
      Collection<? extends GrantedAuthority> authorities,
      PaymentReceipt receipt) {
    Objects.requireNonNull(trustedAttributes, "trustedAttributes must not be null");
    Objects.requireNonNull(authorities, "authorities must not be null");

    Map<String, String> attributes = new HashMap<>(trustedAttributes);
    // Identity facts belong to the authentication result, not caller-provided attributes.
    attributes.put("tokenId", Objects.requireNonNull(tokenId, "tokenId must not be null"));
    attributes.put(
        "protocolScheme",
        Objects.requireNonNull(protocolScheme, "protocolScheme must not be null"));
    if (serviceName != null) {
      attributes.put("serviceName", serviceName);
    } else {
      attributes.remove("serviceName");
    }
    return new PaygateAuthenticationToken(
        tokenId, serviceName, protocolScheme, authorities, attributes, receipt);
  }

  // ========== AbstractAuthenticationToken overrides ==========

  /**
   * Prevents external callers from forging authentication by calling {@code
   * setAuthenticated(true)}. Only the static factory methods and internal constructors (via {@code
   * super.setAuthenticated(true)}) may mark a token as authenticated.
   */
  @Override
  public void setAuthenticated(boolean isAuthenticated) throws IllegalArgumentException {
    if (isAuthenticated) {
      throw new IllegalArgumentException(
          "Cannot set this token to trusted — use the static factory methods");
    }
    super.setAuthenticated(false);
  }

  @Override
  public Object getCredentials() {
    return "[REDACTED]";
  }

  @Override
  public Object getPrincipal() {
    if (tokenId != null) {
      return tokenId;
    }
    return "[unauthenticated]";
  }

  // ========== Accessors ==========

  public String getTokenId() {
    return tokenId;
  }

  public String getServiceName() {
    return serviceName;
  }

  public L402Credential getL402Credential() {
    return null;
  }

  public PaymentCredential getPaymentCredential() {
    return null;
  }

  /** Returns the current-request receipt, if the provider produced one. */
  PaymentReceipt getReceipt() {
    return receipt;
  }

  ReceiptRequest getReceiptRequest() {
    return receiptRequest;
  }

  record ReceiptRequest(
      long priceSats, long timeoutSeconds, String description, String capability) {}

  public String getProtocolScheme() {
    return protocolScheme;
  }

  public String getAuthorizationHeader() {
    return authorizationHeader;
  }

  public Map<String, String> getAttributes() {
    return attributes;
  }

  public String getAttribute(String key) {
    return attributes.get(key);
  }

  public L402HeaderComponents getComponents() {
    return components;
  }

  public Map<String, String> getRequestMetadata() {
    return requestMetadata;
  }
}
