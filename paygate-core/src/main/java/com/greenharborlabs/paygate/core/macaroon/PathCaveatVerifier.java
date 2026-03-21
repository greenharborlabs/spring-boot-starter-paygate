package com.greenharborlabs.paygate.core.macaroon;

import com.greenharborlabs.paygate.core.protocol.ErrorCode;
import com.greenharborlabs.paygate.core.protocol.L402Exception;

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
            throw new L402Exception(ErrorCode.INVALID_SERVICE,
                    "Request path missing from verification context", null);
        }

        // 2. Split caveat value by comma and trim
        String[] rawPatterns = caveat.value().split(",", -1);

        // 3. Reject if pattern count exceeds max (check early before iteration)
        if (rawPatterns.length > maxValuesPerCaveat) {
            throw new L402Exception(ErrorCode.INVALID_SERVICE,
                    "Path caveat contains " + rawPatterns.length
                            + " patterns, exceeding maximum of " + maxValuesPerCaveat, null);
        }

        // 4. Reject if any pattern is empty after trim
        for (String raw : rawPatterns) {
            if (raw.trim().isEmpty()) {
                throw new L402Exception(ErrorCode.INVALID_SERVICE,
                        "Empty path pattern in caveat value", null);
            }
        }

        // 5. Validate each pattern
        String[] patterns = new String[rawPatterns.length];
        for (int i = 0; i < rawPatterns.length; i++) {
            patterns[i] = rawPatterns[i].trim();
            try {
                PathGlobMatcher.validatePattern(patterns[i]);
            } catch (IllegalArgumentException e) {
                throw new L402Exception(ErrorCode.INVALID_SERVICE,
                        "Invalid path pattern: " + e.getMessage(), null);
            }
        }

        // 6. Reject encoded slashes — prevents path traversal attacks.
        //    Check is case-insensitive on the hex digits.
        if (containsEncodedSlash(requestPath)) {
            throw new L402Exception(ErrorCode.INVALID_SERVICE,
                    "Request path contains encoded slash", null);
        }

        // 7. Normalize request path
        String normalizedPath = PathGlobMatcher.normalizePath(requestPath);

        // 8. Match against each pattern — return on first match
        for (String pattern : patterns) {
            if (PathGlobMatcher.matches(pattern, normalizedPath)) {
                return;
            }
        }

        // 9. No pattern matched — reject
        throw new L402Exception(ErrorCode.INVALID_SERVICE,
                "Request path does not match any allowed path pattern", null);
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
