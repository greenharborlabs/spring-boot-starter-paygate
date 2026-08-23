package com.greenharborlabs.paygate.spring;

import java.util.LinkedHashSet;

/** Normalizes and bounds endpoint capability declarations before registry insertion. */
final class CapabilityDeclarationNormalizer {

  private static final String NO_CAPABILITY_SENTINEL = "~";

  private CapabilityDeclarationNormalizer() {}

  static PaygateEndpointConfig normalize(PaygateEndpointConfig config, int maxValuesPerCaveat) {
    String declaration = config.capability();
    String normalized = normalizeDeclaration(declaration, maxValuesPerCaveat);
    if (normalized.equals(declaration)) {
      return config;
    }
    return new PaygateEndpointConfig(
        config.httpMethod(),
        config.pathPattern(),
        config.priceSats(),
        config.timeoutSeconds(),
        config.description(),
        config.pricingStrategy(),
        normalized,
        config.pricingStability());
  }

  private static String normalizeDeclaration(String declaration, int maxValuesPerCaveat) {
    if (declaration == null || declaration.isBlank()) {
      return "";
    }
    int splitLimit =
        maxValuesPerCaveat == Integer.MAX_VALUE ? Integer.MAX_VALUE : maxValuesPerCaveat + 1;
    String[] segments = declaration.split(",", splitLimit);
    if (segments.length > maxValuesPerCaveat) {
      throw new IllegalArgumentException(
          "Capability declaration has "
              + segments.length
              + " values, maximum allowed is "
              + maxValuesPerCaveat);
    }

    var capabilities = new LinkedHashSet<String>();
    for (String segment : segments) {
      String capability = segment.trim();
      if (capability.isEmpty()) {
        throw new IllegalArgumentException("Capability declaration contains a blank segment");
      }
      if (NO_CAPABILITY_SENTINEL.equals(capability)) {
        throw new IllegalArgumentException(
            "Capability '~' is reserved for the internal no-capability state");
      }
      capabilities.add(capability);
    }
    return String.join(",", capabilities);
  }
}
