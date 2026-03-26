package com.greenharborlabs.paygate.core.macaroon;

import com.greenharborlabs.paygate.core.protocol.ErrorCode;
import com.greenharborlabs.paygate.core.protocol.L402Exception;

final class CaveatValues {

    private CaveatValues() {
    }

    static String[] splitBounded(String value, int max, String caveatName) {
        String[] segments = value.split(",", -1);
        if (segments.length > max) {
            throw new L402Exception(ErrorCode.INVALID_SERVICE,
                    "Caveat '" + caveatName + "' has " + segments.length
                            + " values, maximum allowed is " + max,
                    null);
        }
        for (int i = 0; i < segments.length; i++) {
            segments[i] = segments[i].trim();
            if (segments[i].isEmpty()) {
                throw new L402Exception(ErrorCode.INVALID_SERVICE,
                        "Empty segment in caveat '" + caveatName + "'",
                        null);
            }
        }
        return segments;
    }

    static boolean withinBounds(String value, int max) {
        return value.split(",", -1).length <= max;
    }
}
