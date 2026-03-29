package com.greenharborlabs.paygate.spring.security;

import java.util.Set;

/**
 * Resolves capability strings for an authenticated payment token.
 *
 * <p>Implementations may chain multiple resolution strategies (cache, caveat extraction,
 * request metadata) and return the first non-empty result.
 */
@FunctionalInterface
public interface CapabilityResolver {

    /**
     * Resolves capabilities for the given context.
     *
     * @param context the resolution context carrying token, credential, and request information
     * @return a non-null, possibly empty set of capability strings
     */
    Set<String> resolve(CapabilityResolutionContext context);
}
