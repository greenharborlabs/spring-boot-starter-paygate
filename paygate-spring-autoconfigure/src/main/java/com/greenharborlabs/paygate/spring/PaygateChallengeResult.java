package com.greenharborlabs.paygate.spring;

/**
 * Immutable result of an L402 challenge creation, containing all data needed
 * to write the 402 Payment Required response.
 *
 * @param macaroonBase64       base64-encoded serialized macaroon
 * @param bolt11               Lightning BOLT 11 invoice string
 * @param wwwAuthenticateHeader the fully formed WWW-Authenticate header value
 * @param priceSats            the effective price in satoshis
 * @param description          endpoint description
 * @param testPreimage         hex-encoded preimage (non-null only in test mode)
 */
public record PaygateChallengeResult(
        String macaroonBase64,
        String bolt11,
        String wwwAuthenticateHeader,
        long priceSats,
        String description,
        String testPreimage
) {}
