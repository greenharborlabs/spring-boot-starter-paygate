package com.greenharborlabs.paygate.core.macaroon;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * JMH benchmarks for {@link PathCaveatVerifier#verify} with varying pattern counts.
 *
 * <p>Run with: {@code ./gradlew :paygate-core:jmh}
 *
 * <p>Each benchmark creates a caveat with N comma-separated glob patterns where
 * the last pattern matches the request path, forcing a full scan through all
 * preceding non-matching patterns. This measures worst-case verification time.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class PathCaveatVerifierBenchmark {

    private static final int MAX_VALUES_PER_CAVEAT = 50;

    private PathCaveatVerifier verifier;
    private L402VerificationContext context;

    private Caveat caveat1Pattern;
    private Caveat caveat5Patterns;
    private Caveat caveat20Patterns;

    @Setup
    public void setup() {
        verifier = new PathCaveatVerifier(MAX_VALUES_PER_CAVEAT);

        // Request path: a realistic 3-segment API path
        String requestPath = "/api/v2/products/42/reviews";
        context = new L402VerificationContext(
                "benchmark-service",
                Instant.now(),
                Map.of(VerificationContextKeys.REQUEST_PATH, requestPath)
        );

        // 1-pattern caveat: single glob that matches
        caveat1Pattern = new Caveat("path", "/api/v2/products/*/reviews");

        // 5-pattern caveat: 4 non-matching + 1 matching (last)
        caveat5Patterns = new Caveat("path", buildPatterns(5, "/api/v2/products/*/reviews"));

        // 20-pattern caveat: 19 non-matching + 1 matching (last)
        caveat20Patterns = new Caveat("path", buildPatterns(20, "/api/v2/products/*/reviews"));
    }

    @Benchmark
    public void verify1Pattern() {
        verifier.verify(caveat1Pattern, context);
    }

    @Benchmark
    public void verify5Patterns() {
        verifier.verify(caveat5Patterns, context);
    }

    @Benchmark
    public void verify20Patterns() {
        verifier.verify(caveat20Patterns, context);
    }

    /**
     * Builds a comma-separated pattern string with {@code count - 1} non-matching
     * patterns followed by the given matching pattern. Non-matching patterns use
     * realistic but distinct path prefixes to avoid accidental matches.
     */
    private static String buildPatterns(int count, String matchingPattern) {
        // Generate diverse non-matching patterns with varying depths
        String nonMatching = IntStream.range(0, count - 1)
                .mapToObj(i -> switch (i % 4) {
                    case 0 -> "/admin/users/" + i + "/**";
                    case 1 -> "/internal/health/check" + i;
                    case 2 -> "/billing/invoices/*/items/" + i;
                    default -> "/reports/monthly/" + i + "/summary";
                })
                .collect(Collectors.joining(","));
        return nonMatching + "," + matchingPattern;
    }
}
