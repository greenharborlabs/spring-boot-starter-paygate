package com.greenharborlabs.l402.spring.security;

import com.greenharborlabs.l402.core.macaroon.Caveat;
import com.greenharborlabs.l402.core.protocol.L402Credential;
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
public final class L402AuthenticationToken extends AbstractAuthenticationToken {

    private final String rawMacaroon;
    private final String rawPreimage;
    private final L402Credential credential;
    private final String tokenId;
    private final String serviceName;
    private final Map<String, String> attributes;
    private final String requestedCapability;

    /**
     * Creates an unauthenticated token from raw header components.
     */
    public L402AuthenticationToken(String rawMacaroon, String rawPreimage) {
        this(rawMacaroon, rawPreimage, null);
    }

    /**
     * Creates an unauthenticated token from raw header components with an optional requested capability.
     *
     * @param rawMacaroon         base64-encoded macaroon, must not be null
     * @param rawPreimage         hex-encoded preimage, must not be null
     * @param requestedCapability the capability being requested, may be null (permissive)
     */
    public L402AuthenticationToken(String rawMacaroon, String rawPreimage, String requestedCapability) {
        super(Collections.emptyList());
        this.rawMacaroon = Objects.requireNonNull(rawMacaroon, "rawMacaroon must not be null");
        this.rawPreimage = Objects.requireNonNull(rawPreimage, "rawPreimage must not be null");
        this.credential = null;
        this.tokenId = null;
        this.serviceName = null;
        this.attributes = Collections.emptyMap();
        this.requestedCapability = requestedCapability;
        setAuthenticated(false);
    }

    /**
     * Creates an authenticated token from a validated credential.
     */
    public L402AuthenticationToken(L402Credential credential, String serviceName,
                                    Collection<? extends GrantedAuthority> authorities,
                                    Map<String, String> attributes) {
        super(List.copyOf(authorities));
        this.rawMacaroon = null;
        this.rawPreimage = null;
        this.credential = Objects.requireNonNull(credential, "credential must not be null");
        this.tokenId = credential.tokenId();
        this.serviceName = serviceName;
        this.attributes = Map.copyOf(attributes);
        this.requestedCapability = null;
        setAuthenticated(true);
    }

    /**
     * Creates an authenticated token from a validated credential, extracting attributes from caveats.
     */
    public static L402AuthenticationToken authenticated(L402Credential credential, String serviceName) {
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

        return new L402AuthenticationToken(credential, serviceName, authorities, attrs);
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

    public String getRawMacaroon() {
        return rawMacaroon;
    }

    public String getRawPreimage() {
        return rawPreimage;
    }
}
