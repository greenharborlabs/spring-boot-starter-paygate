package com.greenharborlabs.paygate.spring.security;

import com.greenharborlabs.paygate.api.PaymentCredential;
import com.greenharborlabs.paygate.core.macaroon.Caveat;
import com.greenharborlabs.paygate.core.protocol.L402Credential;
import com.greenharborlabs.paygate.core.protocol.L402HeaderComponents;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Spring Security authentication token for payment credentials (L402 and MPP).
 *
 * <p>Before authentication: holds raw credential data from the Authorization header.
 * After authentication: holds a validated credential with tokenId, service name,
 * and protocol-specific attributes accessible via SpEL in {@code @PreAuthorize} expressions.
 *
 * <p>Two unauthenticated paths exist:
 * <ul>
 *   <li>L402 path: created from {@link L402HeaderComponents} (parsed macaroon + preimage)</li>
 *   <li>Protocol-agnostic path: created from a raw Authorization header string</li>
 * </ul>
 *
 * <p>Two authenticated paths exist:
 * <ul>
 *   <li>L402-only: created via {@link #authenticated(L402Credential, String)}</li>
 *   <li>Protocol-agnostic: created via {@link #authenticated(PaymentCredential, String)}</li>
 * </ul>
 */
public final class PaygateAuthenticationToken extends AbstractAuthenticationToken {

    // --- L402-specific unauthenticated fields ---
    private final L402HeaderComponents components;

    // --- Protocol-agnostic unauthenticated field ---
    private final String authorizationHeader;

    // --- L402-only authenticated field (preserved for backward compatibility) ---
    private final L402Credential credential;

    // --- Protocol-agnostic authenticated fields ---
    private final PaymentCredential paymentCredential;
    private final String protocolScheme;

    // --- Common fields ---
    private final String tokenId;
    private final String serviceName;
    private final Map<String, String> attributes;
    private final Map<String, String> requestMetadata;

    // ========== L402 Unauthenticated Constructors (preserved) ==========

    /**
     * Creates an unauthenticated token from parsed L402 header components.
     */
    public PaygateAuthenticationToken(L402HeaderComponents components) {
        this(components, Collections.emptyMap());
    }

    /**
     * Creates an unauthenticated token from parsed header components with request metadata
     * for delegation caveat verification.
     *
     * @param components      parsed L402 header components, must not be null
     * @param requestMetadata request metadata (path, method, client IP, capability), must not be null
     */
    public PaygateAuthenticationToken(L402HeaderComponents components,
                                     Map<String, String> requestMetadata) {
        super(Collections.emptyList());
        this.components = Objects.requireNonNull(components, "components must not be null");
        this.authorizationHeader = null;
        this.credential = null;
        this.paymentCredential = null;
        this.protocolScheme = null;
        this.tokenId = null;
        this.serviceName = null;
        this.attributes = Collections.emptyMap();
        this.requestMetadata = Map.copyOf(
                Objects.requireNonNull(requestMetadata, "requestMetadata must not be null"));
        setAuthenticated(false);
    }

    // ========== Protocol-Agnostic Unauthenticated Constructor ==========

    /**
     * Creates an unauthenticated token from a raw Authorization header for protocol-agnostic
     * authentication. Used when the filter detects a non-L402 credential (e.g., MPP).
     *
     * @param authorizationHeader raw Authorization header value, must not be null
     * @param requestMetadata     request metadata (path, method, client IP, capability), must not be null
     * @return unauthenticated token carrying the raw header
     */
    public static PaygateAuthenticationToken unauthenticated(String authorizationHeader,
                                                             Map<String, String> requestMetadata) {
        return new PaygateAuthenticationToken(
                Objects.requireNonNull(authorizationHeader, "authorizationHeader must not be null"),
                Objects.requireNonNull(requestMetadata, "requestMetadata must not be null"),
                null);
    }

    /**
     * Private constructor for protocol-agnostic unauthenticated tokens.
     * The unused marker parameter disambiguates from L402 constructors.
     */
    private PaygateAuthenticationToken(String authorizationHeader,
                                      Map<String, String> requestMetadata, Void marker) {
        super(Collections.emptyList());
        this.components = null;
        this.authorizationHeader = authorizationHeader;
        this.credential = null;
        this.paymentCredential = null;
        this.protocolScheme = null;
        this.tokenId = null;
        this.serviceName = null;
        this.attributes = Collections.emptyMap();
        this.requestMetadata = Map.copyOf(requestMetadata);
        setAuthenticated(false);
    }

    // ========== L402-Only Authenticated Constructor (preserved) ==========

    /**
     * Private constructor for L402 authenticated tokens.
     */
    private PaygateAuthenticationToken(L402Credential credential, String serviceName,
                                    Collection<? extends GrantedAuthority> authorities,
                                    Map<String, String> attributes) {
        super(List.copyOf(authorities));
        this.components = null;
        this.authorizationHeader = null;
        this.credential = Objects.requireNonNull(credential, "credential must not be null");
        this.paymentCredential = null;
        this.protocolScheme = "L402";
        this.tokenId = credential.tokenId();
        this.serviceName = serviceName;
        this.attributes = Map.copyOf(attributes);
        this.requestMetadata = Collections.emptyMap();
        super.setAuthenticated(true);
    }

    // ========== Protocol-Agnostic Authenticated Constructor ==========

    /**
     * Private constructor for protocol-agnostic authenticated tokens.
     */
    private PaygateAuthenticationToken(PaymentCredential paymentCredential, String serviceName,
                                      Collection<? extends GrantedAuthority> authorities,
                                      Map<String, String> attributes) {
        super(List.copyOf(authorities));
        this.components = null;
        this.authorizationHeader = null;
        this.credential = null;
        this.paymentCredential = Objects.requireNonNull(paymentCredential, "paymentCredential must not be null");
        this.protocolScheme = paymentCredential.sourceProtocolScheme();
        this.tokenId = paymentCredential.tokenId();
        this.serviceName = serviceName;
        this.attributes = Map.copyOf(attributes);
        this.requestMetadata = Collections.emptyMap();
        super.setAuthenticated(true);
    }

    // ========== Static Factories ==========

    /**
     * Creates an authenticated token from a validated L402 credential, extracting attributes from caveats.
     * Delegates to the 3-arg overload with an empty capabilities set.
     */
    public static PaygateAuthenticationToken authenticated(L402Credential credential, String serviceName) {
        return authenticated(credential, serviceName, Set.of());
    }

    /**
     * Creates an authenticated token from a validated L402 credential, extracting attributes from caveats,
     * and adding {@code PAYGATE_CAPABILITY_*} authorities from both caveat-extracted and explicit capabilities.
     *
     * @param credential   validated L402 credential, must not be null
     * @param serviceName  service name, may be null
     * @param capabilities explicit capabilities to grant as {@code PAYGATE_CAPABILITY_*} authorities, must not be null
     * @return authenticated token
     */
    public static PaygateAuthenticationToken authenticated(L402Credential credential, String serviceName,
                                                           Set<String> capabilities) {
        Objects.requireNonNull(capabilities, "capabilities must not be null");

        Map<String, String> attrs = new HashMap<>();
        for (Caveat caveat : credential.macaroon().caveats()) {
            attrs.put(caveat.key(), caveat.value());
        }
        // Built-in attributes placed after caveats so attacker-controlled caveat keys
        // cannot overwrite trusted values like tokenId and serviceName.
        attrs.put("tokenId", credential.tokenId());
        if (serviceName != null) {
            attrs.put("serviceName", serviceName);
        }

        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_PAYMENT"));
        authorities.add(new SimpleGrantedAuthority("ROLE_L402"));

        // Collect caveat-extracted capabilities for dual-emit (L402_CAPABILITY_ + PAYGATE_CAPABILITY_)
        Set<String> caveatCapabilities = new LinkedHashSet<>();
        if (serviceName != null) {
            String capabilitiesKey = serviceName + "_capabilities";
            for (Caveat caveat : credential.macaroon().caveats()) {
                if (capabilitiesKey.equals(caveat.key())) {
                    Arrays.stream(caveat.value().split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .forEach(caveatCapabilities::add);
                }
            }
        }

        // L402_CAPABILITY_* from caveats
        for (String cap : caveatCapabilities) {
            authorities.add(new SimpleGrantedAuthority("L402_CAPABILITY_" + cap));
        }

        // PAYGATE_CAPABILITY_* from caveat-extracted + explicit set (merged, deduplicated by LinkedHashSet).
        // Caveats are the authoritative source: always emit PAYGATE_CAPABILITY_* from caveat capabilities,
        // plus any explicit capabilities provided by the cache.
        Set<String> allPaygateCapabilities = new LinkedHashSet<>(caveatCapabilities);
        for (String cap : capabilities) {
            if (cap != null) {
                allPaygateCapabilities.add(cap);
            }
        }
        for (String cap : allPaygateCapabilities) {
            authorities.add(new SimpleGrantedAuthority("PAYGATE_CAPABILITY_" + cap));
        }

        return new PaygateAuthenticationToken(credential, serviceName, authorities, attrs);
    }

    /**
     * Creates an authenticated token from a validated protocol-agnostic payment credential.
     * Delegates to the 3-arg overload with an empty capabilities set.
     *
     * @param paymentCredential validated payment credential, must not be null
     * @param serviceName       service name, may be null
     * @return authenticated token
     */
    public static PaygateAuthenticationToken authenticated(PaymentCredential paymentCredential,
                                                           String serviceName) {
        return authenticated(paymentCredential, serviceName, Set.of());
    }

    /**
     * Creates an authenticated token from a validated protocol-agnostic payment credential,
     * adding {@code PAYGATE_CAPABILITY_*} authorities from the explicit capabilities set.
     *
     * <p>For L402 credentials, grants {@code ROLE_L402} and {@code ROLE_PAYMENT} authorities.
     * For MPP credentials, grants {@code ROLE_PAYMENT} authority with simpler attributes.
     *
     * @param paymentCredential validated payment credential, must not be null
     * @param serviceName       service name, may be null
     * @param capabilities      explicit capabilities to grant as {@code PAYGATE_CAPABILITY_*} authorities, must not be null
     * @return authenticated token
     */
    public static PaygateAuthenticationToken authenticated(PaymentCredential paymentCredential,
                                                           String serviceName,
                                                           Set<String> capabilities) {
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

        return new PaygateAuthenticationToken(paymentCredential, serviceName, authorities, attrs);
    }

    // ========== AbstractAuthenticationToken overrides ==========

    /**
     * Prevents external callers from forging authentication by calling
     * {@code setAuthenticated(true)}. Only the static factory methods and
     * internal constructors (via {@code super.setAuthenticated(true)}) may
     * mark a token as authenticated.
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
        if (credential != null) {
            return credential;
        }
        if (paymentCredential != null) {
            return paymentCredential;
        }
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
        return credential;
    }

    public PaymentCredential getPaymentCredential() {
        return paymentCredential;
    }

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
