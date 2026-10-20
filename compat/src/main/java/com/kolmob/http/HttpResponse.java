package com.kolmob.http;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

/** Android replacement for the subset of java.net.http.HttpResponse that KoLmafia uses. */
public interface HttpResponse<T> {
  int statusCode();

  T body();

  HttpHeaders headers();

  URI uri();

  HttpRequest request();

  interface BodyHandler<T> {
    /** Not part of the JDK API: converts the response stream into the body object. */
    T apply(InputStream body) throws IOException;
  }

  class BodyHandlers {
    private BodyHandlers() {}

    public static BodyHandler<String> ofString() {
      return ofString(StandardCharsets.UTF_8);
    }

    public static BodyHandler<String> ofString(Charset cs) {
      return in -> new String(readAll(in), cs);
    }

    public static BodyHandler<InputStream> ofInputStream() {
      return in -> in;
    }

    public static BodyHandler<byte[]> ofByteArray() {
      return BodyHandlers::readAll;
    }

    public static BodyHandler<Stream<String>> ofLines() {
      return in -> {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        return r.lines().onClose(() -> {
          try {
            r.close();
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        });
      };
    }

    public static BodyHandler<Void> discarding() {
      return in -> {
        in.close();
        return null;
      };
    }

    static byte[] readAll(InputStream in) throws IOException {
      try (in) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
      }
    }
  }
}
