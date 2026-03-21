package com.greenharborlabs.paygate.core.macaroon;

import com.greenharborlabs.paygate.core.protocol.ErrorCode;
import com.greenharborlabs.paygate.core.protocol.L402Exception;

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
}
