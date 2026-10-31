package com.greenharborlabs.paygate.lightning.lnbits;

/** Removes credentials from LNbits base URLs before they reach logs or exception messages. */
final class LnbitsUrlRedaction {

  static final String REDACTED_URL = "[redacted-url]";

  private LnbitsUrlRedaction() {}

  /**
   * Returns {@code url} with any userinfo in its authority replaced by {@code ***}. A value that
   * contains {@code @} but has no {@code //} authority marker is replaced wholesale, because its
   * userinfo cannot be located reliably.
   */
  static String redactUserInfo(String url) {
    if (url == null) {
      return null;
    }
    int authorityMarker = url.indexOf("//");
    if (authorityMarker < 0) {
      return url.indexOf('@') < 0 ? url : REDACTED_URL;
    }
    int authorityStart = authorityMarker + 2;
    int authorityEnd = url.length();
    for (int index = authorityStart; index < url.length(); index++) {
      char character = url.charAt(index);
      if (character == '/' || character == '?' || character == '#') {
        authorityEnd = index;
        break;
      }
    }
    int userInfoEnd = url.lastIndexOf('@', authorityEnd - 1);
    if (userInfoEnd < authorityStart) {
      return url;
    }
    return url.substring(0, authorityStart) + "***@" + url.substring(userInfoEnd + 1);
  }
}
