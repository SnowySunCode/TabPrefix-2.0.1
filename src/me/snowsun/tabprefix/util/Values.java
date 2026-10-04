package me.snowsun.tabprefix.util;

import java.util.LinkedHashMap;
import java.util.Map;

public final class Values {
  private Values() {}

  public static Map<String, String> of(Object... pairs) {
    if (pairs.length % 2 != 0) throw new IllegalArgumentException("Expected key/value pairs.");
    Map<String, String> result = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2)
      result.put(String.valueOf(pairs[i]), String.valueOf(pairs[i + 1]));
    return result;
  }
}
