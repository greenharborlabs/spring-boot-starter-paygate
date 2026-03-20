package com.greenharborlabs.paygate.core.macaroon;

import com.greenharborlabs.paygate.core.protocol.ErrorCode;
import com.greenharborlabs.paygate.core.protocol.L402Exception;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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

    public CapabilitiesCaveatVerifier(String serviceName) {
        this.serviceName = Objects.requireNonNull(serviceName, "serviceName must not be null");
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

        Set<String> allowed = parseCapabilities(caveat.value());
        if (allowed.isEmpty()) {
            throw new L402Exception(ErrorCode.INVALID_SERVICE,
                    "Empty capabilities list", null);
        }

        if (!allowed.contains(requested)) {
            throw new L402Exception(ErrorCode.INVALID_SERVICE,
                    "Capability '" + requested + "' not allowed", null);
        }
    }

    @Override
    public boolean isMoreRestrictive(Caveat previous, Caveat current) {
        Set<String> previousSet = parseCapabilities(previous.value());
        Set<String> currentSet = parseCapabilities(current.value());
        return previousSet.containsAll(currentSet);
    }

    private static Set<String> parseCapabilities(String value) {
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }
}
