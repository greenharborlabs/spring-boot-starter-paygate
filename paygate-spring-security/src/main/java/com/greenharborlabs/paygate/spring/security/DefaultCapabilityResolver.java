package com.greenharborlabs.paygate.spring.security;

import com.greenharborlabs.paygate.core.macaroon.Caveat;
import com.greenharborlabs.paygate.core.macaroon.VerificationContextKeys;
import com.greenharborlabs.paygate.core.protocol.L402Credential;
import com.greenharborlabs.paygate.spring.CapabilityCache;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Default multi-strategy capability resolver that chains:
 * <ol>
 *   <li>Cache lookup (if {@link CapabilityCache} is available)</li>
 *   <li>L402 caveat extraction (if an {@link L402Credential} with a macaroon is present)</li>
 *   <li>Request metadata fallback (using {@link VerificationContextKeys#REQUESTED_CAPABILITY})</li>
 * </ol>
 *
 * <p>Returns the first non-empty result. Returns an empty set only when all strategies
 * produce nothing.
 */
public final class DefaultCapabilityResolver implements CapabilityResolver {

    private static final System.Logger log = System.getLogger(DefaultCapabilityResolver.class.getName());

    private final CapabilityCache capabilityCache;
    private final String serviceName;

    public DefaultCapabilityResolver(CapabilityCache capabilityCache, String serviceName) {
        this.capabilityCache = capabilityCache;
        this.serviceName = serviceName;
    }

    @Override
    public Set<String> resolve(CapabilityResolutionContext context) {
        String tokenId = context.tokenId();
        if (tokenId == null) {
            return Set.of();
        }

        // Strategy 1: Cache lookup
        Set<String> result = resolveFromCache(tokenId);
        if (!result.isEmpty()) {
            log.log(System.Logger.Level.DEBUG,
                    "Capabilities resolved via cache for token {0}", tokenId);
            return result;
        }

        // Strategy 2: L402 caveat extraction
        result = resolveFromCaveats(context);
        if (!result.isEmpty()) {
            log.log(System.Logger.Level.DEBUG, "Capabilities resolved via L402 caveats");
            return result;
        }

        // Strategy 3: Request metadata fallback
        result = resolveFromMetadata(context);
        if (!result.isEmpty()) {
            log.log(System.Logger.Level.DEBUG, "Capabilities resolved via request metadata");
            return result;
        }

        log.log(System.Logger.Level.DEBUG, "No capabilities resolved for token {0}", tokenId);
        return Set.of();
    }

    private Set<String> resolveFromCache(String tokenId) {
        if (capabilityCache == null) {
            return Set.of();
        }
        try {
            String cached = capabilityCache.get(tokenId);
            if (cached != null) {
                return Set.of(cached);
            }
        } catch (RuntimeException e) {
            log.log(System.Logger.Level.WARNING,
                    "Capability cache lookup failed for token; proceeding without cached capability", e);
        }
        return Set.of();
    }

    private Set<String> resolveFromCaveats(CapabilityResolutionContext context) {
        L402Credential credential = context.l402Credential();
        if (credential == null) {
            return Set.of();
        }

        String svcName = context.serviceName() != null ? context.serviceName() : serviceName;
        if (svcName == null) {
            log.log(System.Logger.Level.DEBUG,
                    "Skipping caveat extraction: serviceName is null");
            return Set.of();
        }

        if (credential.macaroon() == null) {
            log.log(System.Logger.Level.DEBUG,
                    "Skipping caveat extraction: macaroon is null");
            return Set.of();
        }

        String capabilitiesKey = svcName + "_capabilities";
        Set<String> capabilities = new LinkedHashSet<>();

        for (Caveat caveat : credential.macaroon().caveats()) {
            if (capabilitiesKey.equals(caveat.key())) {
                for (String cap : caveat.value().split(",", -1)) {
                    String trimmed = cap.trim();
                    if (!trimmed.isEmpty()) {
                        capabilities.add(trimmed);
                    }
                }
            }
        }

        return capabilities.isEmpty() ? Set.of() : Set.copyOf(capabilities);
    }

    private Set<String> resolveFromMetadata(CapabilityResolutionContext context) {
        String requested = context.requestMetadata().get(VerificationContextKeys.REQUESTED_CAPABILITY);
        if (requested != null && !requested.isEmpty()) {
            return Set.of(requested);
        }
        return Set.of();
    }
}
