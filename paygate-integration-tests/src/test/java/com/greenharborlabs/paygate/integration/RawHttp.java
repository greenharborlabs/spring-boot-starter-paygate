package com.greenharborlabs.paygate.integration;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/**
 * Minimal HTTP/1.1 client that writes the request target byte-for-byte, so the embedded container
 * receives dot segments, nested encodings and path parameters exactly as an attacker sends them.
 */
final class RawHttp {

  private static final Pattern L402_MACAROON = Pattern.compile("macaroon=\"([^\"]+)\"");

  private RawHttp() {}

  record Response(int status, Map<String, List<String>> headers, String body) {
    String header(String name) {
      var values = headers.get(name.toLowerCase(Locale.ROOT));
      return values == null || values.isEmpty() ? null : values.getFirst();
    }

    List<String> headers(String name) {
      return headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
    }
  }

  /** An L402 credential minted by the test-mode backend. */
  record L402Credential(String macaroon, String preimage) {
    String authorization() {
      return "L402 " + macaroon + ":" + preimage;
    }
  }

  static Response get(int port, String rawTarget) throws IOException {
    return get(port, rawTarget, Map.of());
  }

  static Response get(int port, String rawTarget, Map<String, String> headers) throws IOException {
    var request = new StringBuilder();
    request.append("GET ").append(rawTarget).append(" HTTP/1.1\r\n");
    request.append("Host: localhost\r\nConnection: close\r\n");
    headers.forEach(
        (name, value) -> request.append(name).append(": ").append(value).append("\r\n"));
    request.append("\r\n");
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(10_000);
      socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
      var buffer = new ByteArrayOutputStream();
      socket.getInputStream().transferTo(buffer);
      return parse(buffer.toString(StandardCharsets.ISO_8859_1));
    }
  }

  /** Requests a paid path without a credential and returns the minted test-mode L402 credential. */
  @SuppressWarnings("unchecked")
  static L402Credential obtainL402Credential(int port, String rawTarget) throws IOException {
    var challenge = get(port, rawTarget);
    if (challenge.status() != 402) {
      throw new IllegalStateException("Expected 402 challenge but got " + challenge.status());
    }
    String header =
        challenge.headers("WWW-Authenticate").stream()
            .filter(value -> value.startsWith("L402"))
            .findFirst()
            .orElseThrow();
    var matcher = L402_MACAROON.matcher(header);
    if (!matcher.find()) {
      throw new IllegalStateException("L402 challenge has no macaroon");
    }
    Map<String, Object> body = JsonMapper.builder().build().readValue(challenge.body(), Map.class);
    return new L402Credential(matcher.group(1), (String) body.get("test_preimage"));
  }

  private static Response parse(String raw) {
    int headerEnd = raw.indexOf("\r\n\r\n");
    String head = raw.substring(0, headerEnd);
    String body = raw.substring(headerEnd + 4);
    String[] lines = head.split("\r\n");
    int status = Integer.parseInt(lines[0].substring(9, 12));
    var headers = new LinkedHashMap<String, List<String>>();
    for (int i = 1; i < lines.length; i++) {
      int colon = lines[i].indexOf(':');
      String name = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
      String value = lines[i].substring(colon + 1).trim();
      headers.merge(
          name,
          List.of(value),
          (left, right) -> {
            var merged = new ArrayList<>(left);
            merged.addAll(right);
            return List.copyOf(merged);
          });
    }
    var transferEncoding = headers.get("transfer-encoding");
    if (transferEncoding != null && transferEncoding.contains("chunked")) {
      body = dechunk(body);
    }
    return new Response(status, Map.copyOf(headers), body);
  }

  private static String dechunk(String chunked) {
    var body = new StringBuilder();
    int position = 0;
    while (position < chunked.length()) {
      int lineEnd = chunked.indexOf("\r\n", position);
      if (lineEnd < 0) {
        break;
      }
      String sizeField = chunked.substring(position, lineEnd);
      int extension = sizeField.indexOf(';');
      int size =
          Integer.parseInt(extension < 0 ? sizeField : sizeField.substring(0, extension), 16);
      if (size == 0) {
        break;
      }
      int start = lineEnd + 2;
      body.append(chunked, start, start + size);
      position = start + size + 2;
    }
    return body.toString();
  }
}
