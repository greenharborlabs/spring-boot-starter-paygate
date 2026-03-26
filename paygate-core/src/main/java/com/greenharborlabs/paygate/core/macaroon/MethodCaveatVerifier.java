package com.greenharborlabs.paygate.core.macaroon;

import com.greenharborlabs.paygate.core.protocol.ErrorCode;
import com.greenharborlabs.paygate.core.protocol.L402Exception;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Verifies that the request HTTP method matches at least one method
 * specified in the {@code method} caveat value (comma-separated).
 *
 * <p>Comparison is case-insensitive. Stateless and thread-safe.
 */
public class MethodCaveatVerifier implements CaveatVerifier {

    private final int maxValuesPerCaveat;

    public MethodCaveatVerifier(int maxValuesPerCaveat) {
        this.maxValuesPerCaveat = maxValuesPerCaveat;
    }

    @Override
    public String getKey() {
        return "method";
    }

    @Override
    public void verify(Caveat caveat, L402VerificationContext context) {
        // 1. Extract request method — fail-closed if absent
        String requestMethod = context.getRequestMetadata()
                .get(VerificationContextKeys.REQUEST_METHOD);
        if (requestMethod == null) {
            throw new L402Exception(ErrorCode.INVALID_SERVICE,
                    "Request method missing from verification context", null);
        }

        // 2. Split, bounds-check, and trim caveat value
        String[] methods = CaveatValues.splitBounded(caveat.value(), maxValuesPerCaveat, "method");

        // 3. Match request method against each allowed method (case-insensitive)
        for (String method : methods) {
            if (requestMethod.equalsIgnoreCase(method)) {
                return;
            }
        }

        // 6. No method matched — reject
        throw new L402Exception(ErrorCode.INVALID_SERVICE,
                "Request method does not match any allowed method", null);
    }

    @Override
    public boolean isMoreRestrictive(Caveat previous, Caveat current) {
        // Reject oversized caveats before expensive subset-containment check
        if (!CaveatValues.withinBounds(previous.value(), maxValuesPerCaveat)
                || !CaveatValues.withinBounds(current.value(), maxValuesPerCaveat)) {
            return false;
        }

        Set<String> previousSet = toMethodSet(previous.value().split(",", -1));
        Set<String> currentSet = toMethodSet(current.value().split(",", -1));
        return previousSet.containsAll(currentSet);
    }

    private static Set<String> toMethodSet(String[] raw) {
        Set<String> methods = new HashSet<>();
        for (String entry : raw) {
            methods.add(entry.trim().toUpperCase(Locale.ROOT));
        }
        return methods;
    }
}
