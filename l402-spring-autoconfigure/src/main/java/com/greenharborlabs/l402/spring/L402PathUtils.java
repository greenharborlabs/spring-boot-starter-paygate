package com.greenharborlabs.l402.spring;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

/**
 * Shared path normalization utilities for L402 endpoint matching.
 *
 * <p>Collapses path traversal sequences ({@code .}, {@code ..}) and decodes
 * percent-encoded traversal attempts ({@code %2e%2e}, double-encoded
 * {@code %252e%252e}) to prevent bypass of protected endpoint matching.
 *
 * <p>This is a stateless utility class with zero external dependencies (JDK only).
 */
public final class L402PathUtils {

    private L402PathUtils() {
        // utility class — no instantiation
    }

    /**
     * Normalizes a raw request URI to collapse path traversal sequences
     * ({@code .} and {@code ..}) before endpoint registry lookup, preventing
     * bypass of protected endpoint matching. Handles percent-encoded traversal
     * sequences ({@code %2e%2e}, double-encoded {@code %252e%252e}) that
     * {@code URI.normalize()} does not decode.
     */
    public static String normalizePath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return "/";
        }

        // Step 1: Iteratively percent-decode until stable (max 3 iterations)
        // to handle double- and triple-encoded traversal sequences.
        String decoded = percentDecodePath(rawPath);
        String prev = rawPath;
        int iterations = 0;
        while (!decoded.equals(prev) && iterations < 3) {
            prev = decoded;
            decoded = percentDecodePath(decoded);
            iterations++;
        }

        // Step 2: Collapse . and .. using a segment stack
        String[] segments = decoded.split("/", -1);
        var stack = new ArrayDeque<String>();
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (!stack.isEmpty()) {
                    stack.removeLast();
                }
            } else {
                stack.addLast(segment);
            }
        }

        // Step 3: Reconstruct
        if (stack.isEmpty()) {
            return "/";
        }
        var sb = new StringBuilder();
        for (String seg : stack) {
            sb.append('/').append(seg);
        }
        return sb.toString();
    }

    /**
     * Percent-decodes a path string without treating {@code +} as space.
     * Handles multi-byte UTF-8 sequences and passes incomplete {@code %} sequences through unchanged.
     */
    public static String percentDecodePath(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        var out = new ByteArrayOutputStream(path.length());
        int i = 0;
        while (i < path.length()) {
            char c = path.charAt(i);
            if (c == '%' && i + 2 < path.length()) {
                int hi = Character.digit(path.charAt(i + 1), 16);
                int lo = Character.digit(path.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) | lo);
                    i += 3;
                } else {
                    // Invalid hex digits — pass '%' through unchanged
                    out.write(c);
                    i++;
                }
            } else {
                // Non-percent character or incomplete sequence at end — pass through as UTF-8
                if (c < 0x80) {
                    out.write(c);
                } else {
                    byte[] bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                    out.writeBytes(bytes);
                }
                i++;
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
