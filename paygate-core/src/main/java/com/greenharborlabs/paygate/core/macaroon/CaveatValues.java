package com.greenharborlabs.paygate.core.macaroon;

final class CaveatValues {

    private CaveatValues() {
    }

    static String[] splitBounded(String value, int max, String caveatName) {
        String[] segments = value.split(",", -1);
        if (segments.length > max) {
            throw new MacaroonVerificationException(VerificationFailureReason.CAVEAT_NOT_MET,
                    "Caveat '" + caveatName + "' has " + segments.length
                            + " values, maximum allowed is " + max);
        }
        for (int i = 0; i < segments.length; i++) {
            segments[i] = segments[i].trim();
            if (segments[i].isEmpty()) {
                throw new MacaroonVerificationException(VerificationFailureReason.CAVEAT_NOT_MET,
                        "Empty segment in caveat '" + caveatName + "'");
            }
        }
        return segments;
    }

    static boolean withinBounds(String value, int max) {
        return value.split(",", -1).length <= max;
    }
}
