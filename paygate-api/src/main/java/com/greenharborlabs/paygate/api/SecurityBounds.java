package com.greenharborlabs.paygate.api;

/**
 * Shared limits for security-sensitive values accepted by Paygate.
 *
 * <p>The validators use direct inclusive comparisons so every {@code long} value, including the
 * extreme values, is handled without arithmetic overflow.
 */
public final class SecurityBounds {

  /** Minimum accepted request body size, in bytes. */
  public static final long MIN_REQUEST_BODY_SIZE_BYTES = 1L;

  /** Maximum accepted request body size, in bytes (16 MiB). */
  public static final long MAX_REQUEST_BODY_SIZE_BYTES = 16_777_216L;

  /** Minimum accepted credential lifetime, in seconds. */
  public static final long MIN_LIFETIME_SECONDS = 1L;

  /** Maximum accepted credential lifetime, in seconds (24 hours). */
  public static final long MAX_LIFETIME_SECONDS = 86_400L;

  /** Minimum accepted price, in satoshis. */
  public static final long MIN_PRICE_SATS = 1L;

  /** Maximum accepted price, in satoshis. */
  public static final long MAX_PRICE_SATS = 2_100_000_000_000_000L;

  /** Minimum accepted IPv6 network prefix length, in bits. */
  public static final int MIN_IPV6_PREFIX_LENGTH = 0;

  /** Maximum accepted IPv6 network prefix length, in bits. */
  public static final int MAX_IPV6_PREFIX_LENGTH = 128;

  private SecurityBounds() {}

  /**
   * Returns whether a request body size is within the supported inclusive range.
   *
   * @param bodySizeBytes request body size in bytes
   * @return {@code true} when the size is between 1 byte and 16 MiB, inclusive
   */
  public static boolean isValidRequestBodySize(long bodySizeBytes) {
    return bodySizeBytes >= MIN_REQUEST_BODY_SIZE_BYTES
        && bodySizeBytes <= MAX_REQUEST_BODY_SIZE_BYTES;
  }

  /**
   * Returns whether a credential lifetime is within the supported inclusive range.
   *
   * @param lifetimeSeconds credential lifetime in seconds
   * @return {@code true} when the lifetime is between 1 second and 24 hours, inclusive
   */
  public static boolean isValidLifetime(long lifetimeSeconds) {
    return lifetimeSeconds >= MIN_LIFETIME_SECONDS && lifetimeSeconds <= MAX_LIFETIME_SECONDS;
  }

  /**
   * Returns whether a price is within the supported inclusive range.
   *
   * @param priceSats price in satoshis
   * @return {@code true} when the price is between 1 and 2,100,000,000,000,000 satoshis, inclusive
   */
  public static boolean isValidPrice(long priceSats) {
    return priceSats >= MIN_PRICE_SATS && priceSats <= MAX_PRICE_SATS;
  }

  /**
   * Returns whether an IPv6 network prefix length is within the supported inclusive range.
   *
   * @param prefixLength IPv6 network prefix length in bits
   * @return {@code true} when the prefix length is between 0 and 128, inclusive
   */
  public static boolean isValidIpv6PrefixLength(int prefixLength) {
    return prefixLength >= MIN_IPV6_PREFIX_LENGTH && prefixLength <= MAX_IPV6_PREFIX_LENGTH;
  }
}
