package com.greenharborlabs.l402.core.macaroon;

import com.greenharborlabs.l402.core.protocol.ErrorCode;
import com.greenharborlabs.l402.core.protocol.L402Exception;

import java.util.HashSet;
import java.util.Set;

public class ServicesCaveatVerifier implements CaveatVerifier {

    @Override
    public String getKey() {
        return "services";
    }

    @Override
    public void verify(Caveat caveat, L402VerificationContext context) {
        String serviceName = context.getServiceName();
        if (serviceName == null) {
            throw new L402Exception(ErrorCode.INVALID_SERVICE,
                    "Service name is null in verification context", null);
        }

        String[] serviceEntries = caveat.value().split(",");
        for (String entry : serviceEntries) {
            String name = entry.split(":")[0].trim();
            if (name.equals(serviceName)) {
                return;
            }
        }

        throw new L402Exception(ErrorCode.INVALID_SERVICE,
                "Service '" + serviceName + "' not found in caveat services list", null);
    }

    /**
     * Returns {@code true} if the current services are a subset of the previous services.
     * An escalation (adding services not in the previous set) returns {@code false}.
     */
    @Override
    public boolean isMoreRestrictive(Caveat previous, Caveat current) {
        Set<String> previousNames = extractServiceNames(previous.value());
        Set<String> currentNames = extractServiceNames(current.value());
        return previousNames.containsAll(currentNames);
    }

    private static Set<String> extractServiceNames(String serviceList) {
        Set<String> names = new HashSet<>();
        for (String entry : serviceList.split(",")) {
            String name = entry.split(":")[0].trim();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }
}
