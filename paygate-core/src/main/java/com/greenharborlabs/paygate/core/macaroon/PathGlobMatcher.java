package com.greenharborlabs.paygate.core.macaroon;

/**
 * Static utility for path normalization and glob matching used by delegation caveat verifiers.
 * Path normalization delegates to {@link PathNormalizer} — the canonical source of truth.
 * Zero external dependencies — JDK only.
 */
public final class PathGlobMatcher {

    private PathGlobMatcher() {}

    /**
     * Normalizes a request path. Delegates to {@link PathNormalizer#normalize(String)}.
     */
    public static String normalizePath(String path) {
        return PathNormalizer.normalize(path);
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
}
