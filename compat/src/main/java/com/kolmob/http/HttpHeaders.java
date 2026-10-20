package com.kolmob.http;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Android replacement for java.net.http.HttpHeaders (case-insensitive names). */
public final class HttpHeaders {
  private final Map<String, List<String>> headers;

  private HttpHeaders(Map<String, List<String>> headers) {
    this.headers = headers;
  }

  /** Not part of the JDK API: builds headers from a URLConnection-style map. */
  public static HttpHeaders of(Map<String, List<String>> source) {
    TreeMap<String, List<String>> m = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    if (source != null) {
      for (Map.Entry<String, List<String>> e : source.entrySet()) {
        if (e.getKey() == null) continue; // the status line
        m.put(e.getKey(), Collections.unmodifiableList(new ArrayList<>(e.getValue())));
      }
    }
    return new HttpHeaders(Collections.unmodifiableMap(m));
  }

  public Optional<String> firstValue(String name) {
    List<String> v = headers.get(name);
    return v == null || v.isEmpty() ? Optional.empty() : Optional.of(v.get(0));
  }

  public List<String> allValues(String name) {
    List<String> v = headers.get(name);
    return v == null ? List.of() : v;
  }

  public Map<String, List<String>> map() {
    return headers;
  }

  @Override
  public String toString() {
    return "HttpHeaders" + headers;
  }
}
