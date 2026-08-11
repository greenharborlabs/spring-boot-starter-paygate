package com.greenharborlabs.paygate.protocol.mpp;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Minimal RFC 8785 (JSON Canonicalization Scheme) implementation.
 *
 * <p>Sorted keys, no whitespace, handles String/Boolean/null/nested Map/List. Numeric values are
 * deliberately unsupported because governed MPP JSON treats numeric input as ambiguous. Zero
 * external dependencies — JDK only.
 */
public final class JcsSerializer {

  private JcsSerializer() {}

  /**
   * Serializes a Map to RFC 8785 canonical JSON. Keys are sorted in Unicode code point order. No
   * whitespace.
   */
  public static String serialize(Map<String, Object> map) {
    if (map == null) {
      return "null";
    }
    var sb = new StringBuilder(256);
    serializeMap(map, sb);
    return sb.toString();
  }

  @SuppressWarnings("unchecked")
  private static void serializeValue(Object value, StringBuilder sb) {
    switch (value) {
      case null -> sb.append("null");
      case Boolean b -> sb.append(b);
      case Number n -> throw new IllegalArgumentException("Numeric values are not supported");
      case String s -> serializeString(s, sb);
      case Map<?, ?> m -> serializeMap((Map<String, Object>) m, sb);
      case List<?> list -> serializeList(list, sb);
      default ->
          throw new IllegalArgumentException("Unsupported type: " + value.getClass().getName());
    }
  }

  private static void serializeMap(Map<String, Object> map, StringBuilder sb) {
    var sorted = new TreeMap<>(map);
    sb.append('{');
    boolean first = true;
    for (var entry : sorted.entrySet()) {
      if (!first) {
        sb.append(',');
      }
      first = false;
      serializeString(entry.getKey(), sb);
      sb.append(':');
      serializeValue(entry.getValue(), sb);
    }
    sb.append('}');
  }

  private static void serializeList(List<?> list, StringBuilder sb) {
    sb.append('[');
    for (int i = 0; i < list.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      serializeValue(list.get(i), sb);
    }
    sb.append(']');
  }

  private static void serializeString(String s, StringBuilder sb) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (Character.isHighSurrogate(c)) {
        if (i + 1 == s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) {
          throw new IllegalArgumentException("String contains an unpaired surrogate");
        }
        sb.append(c);
        sb.append(s.charAt(++i));
        continue;
      }
      if (Character.isLowSurrogate(c)) {
        throw new IllegalArgumentException("String contains an unpaired surrogate");
      }
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\b' -> sb.append("\\b");
        case '\f' -> sb.append("\\f");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append("\\u");
            sb.append(String.format("%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    sb.append('"');
  }
}
