package com.greenharborlabs.paygate.protocol.mpp;

/**
 * Configurable limits for {@link MinimalJsonParser} to prevent resource exhaustion attacks.
 *
 * @param maxDepth          maximum nesting depth (root object = depth 1)
 * @param maxStringLength   maximum parsed (unescaped) string length in characters
 * @param maxKeysPerObject  maximum number of keys in a single JSON object
 * @param maxInputLength    maximum raw input length in characters
 */
public record MppParserLimits(int maxDepth, int maxStringLength, int maxKeysPerObject, int maxInputLength) {

    public MppParserLimits {
        if (maxDepth <= 0) {
            throw new IllegalArgumentException("maxDepth must be positive, got " + maxDepth);
        }
        if (maxStringLength <= 0) {
            throw new IllegalArgumentException("maxStringLength must be positive, got " + maxStringLength);
        }
        if (maxKeysPerObject <= 0) {
            throw new IllegalArgumentException("maxKeysPerObject must be positive, got " + maxKeysPerObject);
        }
        if (maxInputLength <= 0) {
            throw new IllegalArgumentException("maxInputLength must be positive, got " + maxInputLength);
        }
    }

    static MppParserLimits defaults() {
        return new MppParserLimits(5, 8192, 32, 65_536);
    }
}
