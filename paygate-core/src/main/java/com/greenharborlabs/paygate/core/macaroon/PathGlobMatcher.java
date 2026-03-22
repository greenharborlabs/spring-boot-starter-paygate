package com.greenharborlabs.paygate.core.macaroon;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Static utility for path normalization and glob matching used by delegation caveat verifiers.
 * Zero external dependencies — JDK only.
 */
public final class PathGlobMatcher {

    private static final Set<String> RESERVED_ENCODED = Set.of("2f", "3f", "23", "3a");

    private PathGlobMatcher() {}

    /**
     * Normalizes a request path: strips query string, prepends /, collapses slashes,
     * resolves dot-segments, percent-decodes (single-pass, preserving reserved delimiters),
     * and strips trailing slash.
     */
    public static String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }

        // Strip query string
        int queryIdx = path.indexOf('?');
        if (queryIdx >= 0) {
            path = path.substring(0, queryIdx);
        }

        // Prepend / if missing
        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        // Single-pass percent-decode (preserving reserved path delimiters)
        path = decodePercent(path);

        // Collapse consecutive slashes
        path = collapseSlashes(path);

        // Resolve dot-segments per RFC 3986 Section 5.2.4
        path = resolveDotSegments(path);

        // Strip trailing slash unless root
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        return path;
    }

    /**
     * Glob matching: splits pattern and path by / and walks segments pairwise.
     * {@code *} as entire segment matches exactly one segment.
     * {@code *} within a segment is treated as literal.
     * {@code **} as the last segment matches zero or more remaining segments.
     */
    public static boolean matches(String pattern, String path) {
        return matchNormalized(normalizePath(pattern), normalizePath(path));
    }

    /**
     * Glob matching on already-normalized pattern and path. Callers must ensure both
     * arguments have been passed through {@link #normalizePath(String)} beforehand.
     */
    public static boolean matchNormalized(String normalizedPattern, String normalizedPath) {
        // Root-only pattern
        if ("/".equals(normalizedPattern)) {
            return "/".equals(normalizedPath);
        }

        String[] patternSegs = splitSegments(normalizedPattern);
        String[] pathSegs = splitSegments(normalizedPath);

        return matchSegments(patternSegs, pathSegs, 0, 0);
    }

    /**
     * Validates a glob pattern. Rejects patterns where {@code **} appears in a non-terminal position.
     */
    public static void validatePattern(String pattern) {
        String normalized = normalizePath(pattern);
        String[] segments = splitSegments(normalized);
        for (int i = 0; i < segments.length; i++) {
            if ("**".equals(segments[i]) && i < segments.length - 1) {
                throw new IllegalArgumentException(
                        "** must only appear as the last segment in pattern: " + pattern);
            }
        }
    }

    /**
     * Returns true if the proposed pattern is at least as narrow as (contained within) the
     * existing pattern. That is, every path matched by {@code proposedPattern} is also matched
     * by {@code existingPattern}.
     *
     * <p>Conservative: returns false when uncertain.
     */
    public static boolean isContainedIn(String existingPattern, String proposedPattern) {
        return isContainedInNormalized(normalizePath(existingPattern), normalizePath(proposedPattern));
    }

    /**
     * Containment check on already-normalized patterns. Callers must ensure both
     * arguments have been passed through {@link #normalizePath(String)} beforehand.
     */
    public static boolean isContainedInNormalized(String normalizedExisting, String normalizedProposed) {
        String[] existingSegs = splitSegments(normalizedExisting);
        String[] proposedSegs = splitSegments(normalizedProposed);

        int ei = 0;
        int pi = 0;

        while (ei < existingSegs.length && pi < proposedSegs.length) {
            String eSeg = existingSegs[ei];
            String pSeg = proposedSegs[pi];

            // existing ** terminal: existing matches everything remaining — contained
            if ("**".equals(eSeg)) {
                return true;
            }

            // proposed ** but existing is not ** — proposed is broader
            if ("**".equals(pSeg)) {
                return false;
            }

            // existing * accepts any single segment; proposed (literal or *) is same or narrower
            if ("*".equals(eSeg)) {
                ei++;
                pi++;
                continue;
            }

            // proposed * but existing is literal — proposed is broader
            if ("*".equals(pSeg)) {
                return false;
            }

            // both literal — must be equal
            if (!eSeg.equals(pSeg)) {
                return false;
            }

            ei++;
            pi++;
        }

        // Both exhausted — identical structure, contained
        if (ei == existingSegs.length && pi == proposedSegs.length) {
            return true;
        }

        // Proposed exhausted, existing has segments remaining
        if (pi == proposedSegs.length) {
            // Contained only if remaining existing is exactly **
            return ei == existingSegs.length - 1 && "**".equals(existingSegs[ei]);
        }

        // Existing exhausted, proposed has segments remaining — proposed matches paths existing doesn't
        return false;
    }

    private static String[] splitSegments(String normalizedPath) {
        // "/products/123" -> ["products", "123"]
        // "/" -> []
        if ("/".equals(normalizedPath)) {
            return new String[0];
        }
        // Remove leading slash, then split
        return normalizedPath.substring(1).split("/", -1);
    }

    private static boolean matchSegments(String[] patternSegs, String[] pathSegs, int pi, int qi) {
        // Both exhausted — match
        if (pi == patternSegs.length && qi == pathSegs.length) {
            return true;
        }

        // Pattern exhausted but path has more segments — no match
        if (pi == patternSegs.length) {
            return false;
        }

        String seg = patternSegs[pi];

        // ** as terminal: matches zero or more remaining segments
        if ("**".equals(seg)) {
            // Terminal ** — always matches regardless of remaining path segments
            return true;
        }

        // Path exhausted but pattern has more non-** segments — no match
        if (qi == pathSegs.length) {
            return false;
        }

        // * as entire segment: matches exactly one segment (any value)
        if ("*".equals(seg)) {
            return matchSegments(patternSegs, pathSegs, pi + 1, qi + 1);
        }

        // Literal match (including segments containing * as part of the string)
        if (seg.equals(pathSegs[qi])) {
            return matchSegments(patternSegs, pathSegs, pi + 1, qi + 1);
        }

        return false;
    }

    /**
     * Single-pass percent-decode. Decodes all percent-encoded characters except
     * reserved path delimiters: %2F (/), %3F (?), %23 (#), %3A (:).
     */
    private static String decodePercent(String input) {
        int len = input.length();
        var sb = new StringBuilder(len);
        int i = 0;
        while (i < len) {
            char c = input.charAt(i);
            if (c == '%' && i + 2 < len) {
                String hex = input.substring(i + 1, i + 3);
                if (isValidHex(hex)) {
                    // Check if this is a reserved delimiter (case-insensitive)
                    if (RESERVED_ENCODED.contains(hex.toLowerCase())) {
                        // Preserve the percent-encoding, uppercase the hex for consistency
                        sb.append('%').append(hex.toUpperCase());
                        i += 3;
                    } else {
                        int value = Integer.parseInt(hex, 16);
                        sb.append((char) value);
                        i += 3;
                    }
                } else {
                    sb.append(c);
                    i++;
                }
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    private static boolean isValidHex(String hex) {
        if (hex.length() != 2) return false;
        for (int i = 0; i < 2; i++) {
            char c = hex.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }

    private static String collapseSlashes(String path) {
        var sb = new StringBuilder(path.length());
        boolean prevSlash = false;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '/') {
                if (!prevSlash) {
                    sb.append(c);
                }
                prevSlash = true;
            } else {
                sb.append(c);
                prevSlash = false;
            }
        }
        return sb.toString();
    }

    /**
     * Resolves dot-segments per RFC 3986 Section 5.2.4.
     */
    private static String resolveDotSegments(String path) {
        String[] parts = path.split("/", -1);
        List<String> output = new ArrayList<>();
        for (String part : parts) {
            switch (part) {
                case "." -> {
                    // skip
                }
                case ".." -> {
                    if (!output.isEmpty()) {
                        output.removeLast();
                    }
                }
                default -> output.add(part);
            }
        }
        String result = String.join("/", output);
        // Ensure leading slash
        if (!result.startsWith("/")) {
            result = "/" + result;
        }
        return result;
    }
}
