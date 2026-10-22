package com.greenharborlabs.paygate.spring.security;

import com.greenharborlabs.paygate.spring.CapabilityCache;
import com.greenharborlabs.paygate.spring.LogSanitizer;
import java.util.Set;

/**
 * Default capability resolver backed by the server-side {@link CapabilityCache}.
 *
 * <p>This resolver is only a supplement for non-L402 protocols. L402 authorities must be derived
 * from the validator's verified effective capabilities, so an L402 context always resolves to an
 * empty set without consulting cache, caveats, or request metadata. MPP capabilities come from the
 * HMAC-bound challenge via {@link
 * com.greenharborlabs.paygate.api.PaymentProtocol#validateAndResolveCapabilities}.
 *
 * <p>The resolver never grants the capability named in the request metadata ({@code
 * request.capability}). That value describes the endpoint being requested, not what the credential
 * paid for, so trusting it would let a credential bought for one endpoint satisfy another
 * endpoint's capability check.
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

    Set<String> result = resolveFromCache(tokenId);
    if (!result.isEmpty()) {
      log.log(System.Logger.Level.DEBUG, "Capabilities resolved via cache");
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
          "Capability cache lookup failed for token correlation {0}; proceeding without cached capability",
          LogSanitizer.sanitizeTokenId(tokenId));
    }
    return Set.of();
  }
}
