package com.greenharborlabs.paygate.core.macaroon;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Verifies {@code {service}_capabilities} caveats per the L402 spec.
 * The caveat value is a comma-separated list of capability names (e.g., "search,analyze").
 * Verification checks that the requested capability is present in the allowed list.
 *
 * <p>If the context does not specify a requested capability (null), verification is permissive
 * and passes without checking. If the capabilities list in the caveat is empty, all requests
 * are rejected.
 */
public class CapabilitiesCaveatVerifier implements CaveatVerifier {

    private final String serviceName;
    private final int maxValuesPerCaveat;

    public CapabilitiesCaveatVerifier(String serviceName, int maxValuesPerCaveat) {
        this.serviceName = Objects.requireNonNull(serviceName, "serviceName must not be null");
        if (maxValuesPerCaveat < 1) {
            throw new IllegalArgumentException("maxValuesPerCaveat must be >= 1");
        }
        this.maxValuesPerCaveat = maxValuesPerCaveat;
    }

    @Override
    public String getKey() {
        return serviceName + "_capabilities";
    }

    @Override
    public void verify(Caveat caveat, L402VerificationContext context) {
        String requested = context.getRequestedCapability();
        if (requested == null) {
            return;
        }

        String[] segments = CaveatValues.splitBounded(caveat.value(), maxValuesPerCaveat,
                getKey());
        Set<String> allowed = new HashSet<>(Arrays.asList(segments));

        if (!allowed.contains(requested)) {
            throw new MacaroonVerificationException(VerificationFailureReason.CAVEAT_NOT_MET,
                    "Capability '" + requested + "' not allowed");
        }
    }

    @Override
    public boolean isMoreRestrictive(Caveat previous, Caveat current) {
        if (!CaveatValues.withinBounds(previous.value(), maxValuesPerCaveat)
                || !CaveatValues.withinBounds(current.value(), maxValuesPerCaveat)) {
            return false;
        }
        Set<String> previousSet = parseCapabilities(previous.value());
        Set<String> currentSet = parseCapabilities(current.value());
        return previousSet.containsAll(currentSet);
    }

    private static Set<String> parseCapabilities(String value) {
        String[] segments = value.split(",", -1);
        Set<String> result = new HashSet<>();
        for (String segment : segments) {
            String trimmed = segment.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }
}
