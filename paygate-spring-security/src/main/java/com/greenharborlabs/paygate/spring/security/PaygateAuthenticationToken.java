package com.greenharborlabs.paygate.spring.security;

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
 * Spring Security authentication token for L402 credentials.
 *
 * <p>Before authentication: holds raw macaroon and preimage strings from the Authorization header.
 * After authentication: holds a validated {@link L402Credential} with tokenId, service name,
 * and caveat-derived attributes accessible via SpEL in {@code @PreAuthorize} expressions.
 */
public final class PaygateAuthenticationToken extends AbstractAuthenticationToken {

    private final L402HeaderComponents components;
    private final L402Credential credential;
    private final String tokenId;
    private final String serviceName;
    private final Map<String, String> attributes;
    private final String requestedCapability;
    private final Map<String, String> requestMetadata;

    /**
     * Creates an unauthenticated token from parsed header components.
     */
    public PaygateAuthenticationToken(L402HeaderComponents components) {
        this(components, null);
    }

    /**
     * Creates an unauthenticated token from parsed header components with an optional requested capability.
     *
     * @param components          parsed L402 header components, must not be null
     * @param requestedCapability the capability being requested, may be null (permissive)
     */
    public PaygateAuthenticationToken(L402HeaderComponents components, String requestedCapability) {
        this(components, requestedCapability, Collections.emptyMap());
    }

    /**
     * Creates an unauthenticated token from parsed header components with an optional requested
     * capability and request metadata for delegation caveat verification.
     *
     * @param components          parsed L402 header components, must not be null
     * @param requestedCapability the capability being requested, may be null (permissive)
     * @param requestMetadata     request metadata (path, method, client IP), must not be null
     */
    public PaygateAuthenticationToken(L402HeaderComponents components, String requestedCapability,
                                     Map<String, String> requestMetadata) {
        super(Collections.emptyList());
        this.components = Objects.requireNonNull(components, "components must not be null");
        this.credential = null;
        this.tokenId = null;
        this.serviceName = null;
        this.attributes = Collections.emptyMap();
        this.requestedCapability = requestedCapability;
        this.requestMetadata = Map.copyOf(
                Objects.requireNonNull(requestMetadata, "requestMetadata must not be null"));
        setAuthenticated(false);
    }

    /**
     * Creates an authenticated token from a validated credential.
     */
    public PaygateAuthenticationToken(L402Credential credential, String serviceName,
                                    Collection<? extends GrantedAuthority> authorities,
                                    Map<String, String> attributes) {
        super(List.copyOf(authorities));
        this.components = null;
        this.credential = Objects.requireNonNull(credential, "credential must not be null");
        this.tokenId = credential.tokenId();
        this.serviceName = serviceName;
        this.attributes = Map.copyOf(attributes);
        this.requestedCapability = null;
        this.requestMetadata = Collections.emptyMap();
        setAuthenticated(true);
    }

    /**
     * Creates an authenticated token from a validated credential, extracting attributes from caveats.
     */
    public static PaygateAuthenticationToken authenticated(L402Credential credential, String serviceName) {
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
        authorities.add(new SimpleGrantedAuthority("ROLE_L402"));

        if (serviceName != null) {
            String capabilitiesKey = serviceName + "_capabilities";
            for (Caveat caveat : credential.macaroon().caveats()) {
                if (capabilitiesKey.equals(caveat.key())) {
                    Arrays.stream(caveat.value().split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .forEach(cap -> authorities.add(
                                    new SimpleGrantedAuthority("L402_CAPABILITY_" + cap)));
                }
            }
        }

        return new PaygateAuthenticationToken(credential, serviceName, authorities, attrs);
    }

    @Override
    public Object getCredentials() {
        if (credential != null) {
            return credential;
        }
        return "[REDACTED]";
    }

    @Override
    public Object getPrincipal() {
        if (tokenId != null) {
            return tokenId;
        }
        return "[unauthenticated-l402]";
    }

    public String getTokenId() {
        return tokenId;
    }

    public String getServiceName() {
        return serviceName;
    }

    public L402Credential getL402Credential() {
        return credential;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public String getAttribute(String key) {
        return attributes.get(key);
    }

    public String getRequestedCapability() {
        return requestedCapability;
    }

    public L402HeaderComponents getComponents() {
        return components;
    }

    public Map<String, String> getRequestMetadata() {
        return requestMetadata;
    }
}
