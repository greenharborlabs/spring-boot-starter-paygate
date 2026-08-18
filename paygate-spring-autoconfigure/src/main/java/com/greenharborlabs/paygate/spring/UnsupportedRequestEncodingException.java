package com.greenharborlabs.paygate.spring;

/**
 * Raised when a size-priced protected request uses a content encoding other than identity.
 *
 * <p>Pricing from compressed wire content would make the charge depend on an encoding declaration
 * rather than the bounded bytes safely observed and replayed by the server.
 */
public final class UnsupportedRequestEncodingException extends RuntimeException {

  /** Creates the stable, detail-free rejection used by both enforcement paths. */
  public UnsupportedRequestEncodingException() {
    super("Non-identity content encoding is not supported for size-priced requests");
  }
}
