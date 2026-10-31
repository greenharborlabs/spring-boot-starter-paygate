package com.greenharborlabs.paygate.spring;

/**
 * Raised when bounded request-body capture observes fewer bytes than the request declared in its
 * {@code Content-Length} header.
 *
 * <p>A short read means the body was truncated or that another component consumed the stream before
 * Paygate could observe it (for example a form-parameter parsing filter). Pricing or binding a
 * credential to the remaining bytes would describe a different body from the one the application
 * sees, so the request is rejected instead.
 */
public final class RequestBodyIncompleteException extends RuntimeException {

  /** Creates the stable, detail-free rejection used by every capture path. */
  public RequestBodyIncompleteException() {
    super("Observed request body is shorter than the declared Content-Length");
  }
}
