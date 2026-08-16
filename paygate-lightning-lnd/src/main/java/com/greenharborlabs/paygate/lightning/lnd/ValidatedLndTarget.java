package com.greenharborlabs.paygate.lightning.lnd;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;

/**
 * A plaintext LND target that has passed the local-only transport boundary.
 *
 * <p>The validator accepts only a loopback address literal or exact {@code localhost}. Arbitrary
 * DNS names are never resolved. A localhost resolution is captured once and the selected numeric
 * address is used for the connection, avoiding a later DNS-policy decision.
 */
public record ValidatedLndTarget(InetAddress address) {

  /** Injectable DNS seam used only for the exact localhost case. */
  @FunctionalInterface
  public interface Resolver {
    InetAddress[] resolveLocalhost() throws UnknownHostException;
  }

  private static final Resolver SYSTEM_RESOLVER = () -> InetAddress.getAllByName("localhost");

  public ValidatedLndTarget {
    Objects.requireNonNull(address, "address");
    if (!address.isLoopbackAddress()) {
      throw new IllegalArgumentException("Plaintext LND target must be a local loopback address");
    }
  }

  /** Validates the supplied target with the system resolver. */
  public static ValidatedLndTarget validate(String host) {
    return validate(host, SYSTEM_RESOLVER);
  }

  /**
   * Validates a target without resolving arbitrary host names.
   *
   * @param host the configured raw host value
   * @param resolver resolver used only for exact localhost
   * @return an immutable target with a numeric loopback address
   */
  public static ValidatedLndTarget validate(String host, Resolver resolver) {
    Objects.requireNonNull(host, "host");
    Objects.requireNonNull(resolver, "resolver");
    if (host.equalsIgnoreCase("localhost")) {
      return validateLocalhost(resolver);
    }

    try {
      var literal = InetAddress.ofLiteral(host);
      if (!literal.isLoopbackAddress()) {
        throw rejected();
      }
      return new ValidatedLndTarget(literal);
    } catch (IllegalArgumentException e) {
      throw rejected();
    }
  }

  private static ValidatedLndTarget validateLocalhost(Resolver resolver) {
    try {
      var addresses = resolver.resolveLocalhost();
      if (addresses == null || addresses.length == 0) {
        throw rejected();
      }
      for (InetAddress address : addresses) {
        if (address == null || !address.isLoopbackAddress()) {
          throw rejected();
        }
      }
      return new ValidatedLndTarget(addresses[0]);
    } catch (UnknownHostException e) {
      throw rejected();
    }
  }

  private static IllegalArgumentException rejected() {
    return new IllegalArgumentException("Plaintext LND target must be an approved local target");
  }
}
