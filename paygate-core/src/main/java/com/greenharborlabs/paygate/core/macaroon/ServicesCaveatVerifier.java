package com.greenharborlabs.paygate.core.macaroon;

import java.util.HashSet;
import java.util.Set;

public class ServicesCaveatVerifier implements CaveatVerifier {

    private final int maxValuesPerCaveat;

    public ServicesCaveatVerifier(int maxValuesPerCaveat) {
        if (maxValuesPerCaveat < 1) {
            throw new IllegalArgumentException("maxValuesPerCaveat must be >= 1");
        }
        this.maxValuesPerCaveat = maxValuesPerCaveat;
    }

    @Override
    public String getKey() {
        return "services";
    }

    @Override
    public void verify(Caveat caveat, L402VerificationContext context) {
        String serviceName = context.getServiceName();
        if (serviceName == null) {
            throw new MacaroonVerificationException(VerificationFailureReason.CAVEAT_NOT_MET,
                    "Service name is null in verification context");
        }

        String[] serviceEntries = CaveatValues.splitBounded(caveat.value(), maxValuesPerCaveat,
                getKey());
        for (String entry : serviceEntries) {
            String name = entry.split(":")[0].trim();
            if (name.equals(serviceName)) {
                return;
            }
        }

        throw new MacaroonVerificationException(VerificationFailureReason.CAVEAT_NOT_MET,
                "Service '" + serviceName + "' not found in caveat services list");
    }

    /**
     * Returns {@code true} if the current services are a subset of the previous services.
     * An escalation (adding services not in the previous set) returns {@code false}.
     */
    @Override
    public boolean isMoreRestrictive(Caveat previous, Caveat current) {
        if (!CaveatValues.withinBounds(previous.value(), maxValuesPerCaveat)
                || !CaveatValues.withinBounds(current.value(), maxValuesPerCaveat)) {
            return false;
        }
        Set<String> previousNames = extractServiceNames(previous.value());
        Set<String> currentNames = extractServiceNames(current.value());
        return previousNames.containsAll(currentNames);
    }

    private static Set<String> extractServiceNames(String serviceList) {
        Set<String> names = new HashSet<>();
        for (String entry : serviceList.split(",", -1)) {
            String name = entry.split(":")[0].trim();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }
}
