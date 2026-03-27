package com.greenharborlabs.paygate.core.macaroon;

/**
 * Verifies that the request path matches at least one glob pattern
 * specified in the {@code path} caveat value (comma-separated).
 *
 * <p>Stateless and thread-safe.
 */
public class PathCaveatVerifier implements CaveatVerifier {

    private final int maxValuesPerCaveat;

    public PathCaveatVerifier(int maxValuesPerCaveat) {
        this.maxValuesPerCaveat = maxValuesPerCaveat;
    }

    @Override
    public String getKey() {
        return "path";
    }

    @Override
    public void verify(Caveat caveat, L402VerificationContext context) {
        // 1. Extract request path — fail-closed if absent
        String requestPath = context.getRequestMetadata()
                .get(VerificationContextKeys.REQUEST_PATH);
        if (requestPath == null) {
            throw new MacaroonVerificationException(VerificationFailureReason.CAVEAT_NOT_MET,
                    "Request path missing from verification context");
        }

        // 2. Split, bounds-check, and trim caveat value
        String[] patterns = CaveatValues.splitBounded(caveat.value(), maxValuesPerCaveat, "path");

        // 3. Validate each pattern and pre-normalize
        String[] normalizedPatterns = new String[patterns.length];
        for (int i = 0; i < patterns.length; i++) {
            String trimmed = patterns[i];
            try {
                PathGlobMatcher.validatePattern(trimmed);
            } catch (IllegalArgumentException e) {
                throw new MacaroonVerificationException(VerificationFailureReason.CAVEAT_NOT_MET,
                        "Invalid path pattern: " + e.getMessage());
            }
            normalizedPatterns[i] = PathGlobMatcher.normalizePath(trimmed);
        }

        // 6. Reject encoded slashes — prevents path traversal attacks.
        //    Check is case-insensitive on the hex digits.
        if (containsEncodedSlash(requestPath)) {
            throw new MacaroonVerificationException(VerificationFailureReason.CAVEAT_NOT_MET,
                    "Request path contains encoded slash");
        }

        // 7. Normalize request path once
        String normalizedPath = PathGlobMatcher.normalizePath(requestPath);

        // 8. Match against each pre-normalized pattern — return on first match
        for (String normalizedPattern : normalizedPatterns) {
            if (PathGlobMatcher.matchNormalized(normalizedPattern, normalizedPath)) {
                return;
            }
        }

        // 9. No pattern matched — reject
        throw new MacaroonVerificationException(VerificationFailureReason.CAVEAT_NOT_MET,
                "Request path does not match any allowed path pattern");
    }

    @Override
    public boolean isMoreRestrictive(Caveat previous, Caveat current) {
        // Reject oversized caveats before expensive subset-containment check
        if (!CaveatValues.withinBounds(previous.value(), maxValuesPerCaveat)
                || !CaveatValues.withinBounds(current.value(), maxValuesPerCaveat)) {
            return false;
        }

        String[] previousNormalized = trimAndNormalize(previous.value().split(",", -1));
        String[] currentNormalized = trimAndNormalize(current.value().split(",", -1));

        for (String cp : currentNormalized) {
            boolean contained = false;
            for (String pp : previousNormalized) {
                if (PathGlobMatcher.isContainedInNormalized(pp, cp)) {
                    contained = true;
                    break;
                }
            }
            if (!contained) {
                return false;
            }
        }
        return true;
    }

    private static String[] trimAndNormalize(String[] raw) {
        String[] result = new String[raw.length];
        for (int i = 0; i < raw.length; i++) {
            result[i] = PathGlobMatcher.normalizePath(raw[i].trim());
        }
        return result;
    }

    /**
     * Returns true if the path contains a percent-encoded slash (%2F or %2f,
     * case-insensitive on the hex digits).
     */
    private static boolean containsEncodedSlash(String path) {
        int len = path.length();
        for (int i = 0; i <= len - 3; i++) {
            if (path.charAt(i) == '%') {
                char h1 = path.charAt(i + 1);
                char h2 = path.charAt(i + 2);
                if ((h1 == '2') && (h2 == 'F' || h2 == 'f')) {
                    return true;
                }
            }
        }
        return false;
    }
}
