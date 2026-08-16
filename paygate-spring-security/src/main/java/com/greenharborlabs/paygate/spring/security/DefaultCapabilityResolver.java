package com.greenharborlabs.paygate.spring.security;

import com.greenharborlabs.paygate.core.macaroon.VerificationContextKeys;
import com.greenharborlabs.paygate.spring.CapabilityCache;
import java.util.Set;

/**
 * Default multi-strategy capability resolver that chains:
 *
 * <ol>
 *   <li>Cache lookup (if {@link CapabilityCache} is available)
 *   <li>Request metadata fallback (using {@link VerificationContextKeys#REQUESTED_CAPABILITY})
 * </ol>
 *
 * <p>This resolver is only a fallback for non-L402 protocols. L402 authorities must be derived from
 * the validator's verified effective capabilities, so an L402 context always resolves to an empty
 * set without consulting cache, caveats, or request metadata.
 */
public final class DefaultCapabilityResolver implements CapabilityResolver {

  private static final System.Logger log =
      System.getLogger(DefaultCapabilityResolver.class.getName());

  private final CapabilityCache capabilityCache;

  public DefaultCapabilityResolver(CapabilityCache capabilityCache) {
    this.capabilityCache = capabilityCache;
  }

  @Override
  public Set<String> resolve(CapabilityResolutionContext context) {
    if (context.l402Credential() != null) {
      log.log(
          System.Logger.Level.DEBUG, "Skipping fallback capability resolution for L402 credential");
      return Set.of();
    }

    String tokenId = context.tokenId();
    if (tokenId == null) {
      return Set.of();
    }

    // Strategy 1: Cache lookup
    Set<String> result = resolveFromCache(tokenId);
    if (!result.isEmpty()) {
      log.log(System.Logger.Level.DEBUG, "Capabilities resolved via cache");
      return result;
    }

    // Strategy 2: Request metadata fallback
    result = resolveFromMetadata(context);
    if (!result.isEmpty()) {
      log.log(System.Logger.Level.DEBUG, "Capabilities resolved via request metadata");
      return result;
    }

    log.log(System.Logger.Level.DEBUG, "No capabilities resolved");
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
      log.log(
          System.Logger.Level.WARNING,
          "Capability cache lookup failed for token; proceeding without cached capability",
          e);
    }
    return Set.of();
  }

  private Set<String> resolveFromMetadata(CapabilityResolutionContext context) {
    String requested = context.requestMetadata().get(VerificationContextKeys.REQUESTED_CAPABILITY);
    if (requested != null && !requested.isEmpty()) {
      return Set.of(requested);
    }
    return Set.of();
  }
}
