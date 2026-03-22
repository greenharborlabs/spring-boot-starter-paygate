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

        // 2. Split caveat value by comma
        String[] rawMethods = caveat.value().split(",", -1);

        // 3. Reject if method count exceeds max
        if (rawMethods.length > maxValuesPerCaveat) {
            throw new L402Exception(ErrorCode.INVALID_SERVICE,
                    "Method caveat contains " + rawMethods.length
                            + " values, exceeding maximum of " + maxValuesPerCaveat, null);
        }

        // 4. Reject if any method is empty after trim
        for (String raw : rawMethods) {
            if (raw.trim().isEmpty()) {
                throw new L402Exception(ErrorCode.INVALID_SERVICE,
                        "Empty method in caveat value", null);
            }
        }

        // 5. Match request method against each allowed method (case-insensitive)
        for (String raw : rawMethods) {
            if (requestMethod.equalsIgnoreCase(raw.trim())) {
                return;
            }
        }

        // 6. No method matched — reject
        throw new L402Exception(ErrorCode.INVALID_SERVICE,
                "Request method does not match any allowed method", null);
    }

    @Override
    public boolean isMoreRestrictive(Caveat previous, Caveat current) {
        // Split once and reuse for both the guard check and the subset computation
        String[] previousRaw = previous.value().split(",", -1);
        String[] currentRaw = current.value().split(",", -1);

        // Reject oversized caveats before expensive subset-containment check
        if (previousRaw.length > maxValuesPerCaveat
                || currentRaw.length > maxValuesPerCaveat) {
            return false;
        }

        Set<String> previousSet = toMethodSet(previousRaw);
        Set<String> currentSet = toMethodSet(currentRaw);
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
