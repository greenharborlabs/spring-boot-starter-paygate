package com.greenharborlabs.paygate.api;

/**
 * Marker interface for protocol-specific metadata attached to a
 * {@link PaymentCredential}.
 *
 * <p>Each payment protocol module provides its own implementation (e.g.,
 * {@code L402Metadata}, {@code MppMetadata}) containing protocol-specific
 * fields that the core framework does not need to understand. Consumers
 * that know the concrete protocol can safely cast to the expected subtype.
 */
public interface ProtocolMetadata {
}
