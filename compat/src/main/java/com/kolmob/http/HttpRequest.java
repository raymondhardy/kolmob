package com.kolmob.http;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Android replacement for the subset of java.net.http.HttpRequest that KoLmafia uses. */
public abstract class HttpRequest {
  protected HttpRequest() {}

  public interface BodyPublisher {
    /** Not part of the JDK API: the full request body. */
    byte[] bytes();
  }

  public static class BodyPublishers {
    private BodyPublishers() {}

    public static BodyPublisher ofString(String body) {
      return ofString(body, StandardCharsets.UTF_8);
    }

    public static BodyPublisher ofString(String body, Charset cs) {
      byte[] b = body.getBytes(cs);
      return () -> b;
    }

    public static BodyPublisher ofByteArray(byte[] bytes) {
      return () -> bytes;
    }

    public static BodyPublisher noBody() {
      return () -> new byte[0];
    }
  }

  public interface Builder {
    Builder uri(URI uri);

    Builder header(String name, String value);

    Builder setHeader(String name, String value);

    Builder timeout(Duration duration);

    Builder GET();

    Builder POST(BodyPublisher publisher);

    Builder method(String method, BodyPublisher publisher);

    HttpRequest build();
  }

  public static Builder newBuilder() {
    return new BuilderImpl(null);
  }

  public static Builder newBuilder(URI uri) {
    return new BuilderImpl(uri);
  }

  public abstract URI uri();

  public abstract String method();

  public abstract Optional<Duration> timeout();

  public abstract Optional<BodyPublisher> bodyPublisher();

  public abstract HttpHeaders headers();

  private static final class BuilderImpl implements Builder {
    private URI uri;
    private final Map<String, List<String>> headers = new LinkedHashMap<>();
    private Duration timeout;
    private String method = "GET";
    private BodyPublisher body;

    BuilderImpl(URI uri) {
      this.uri = uri;
    }

    @Override
    public Builder uri(URI uri) {
      this.uri = uri;
      return this;
    }

    @Override
    public Builder header(String name, String value) {
      headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
      return this;
    }

    @Override
    public Builder setHeader(String name, String value) {
      List<String> l = new ArrayList<>();
      l.add(value);
      headers.put(name, l);
      return this;
    }

    @Override
    public Builder timeout(Duration duration) {
      this.timeout = duration;
      return this;
    }

    @Override
    public Builder GET() {
      this.method = "GET";
      this.body = null;
      return this;
    }

    @Override
    public Builder POST(BodyPublisher publisher) {
      this.method = "POST";
      this.body = publisher;
      return this;
    }

    @Override
    public Builder method(String method, BodyPublisher publisher) {
      this.method = method;
      this.body = publisher;
      return this;
    }

    @Override
    public HttpRequest build() {
      if (uri == null) throw new IllegalStateException("uri is null");
      final URI u = uri;
      final String m = method;
      final Duration t = timeout;
      final BodyPublisher b = body;
      final HttpHeaders h = HttpHeaders.of(headers);
      return new HttpRequest() {
        @Override
        public URI uri() {
          return u;
        }

        @Override
        public String method() {
          return m;
        }

        @Override
        public Optional<Duration> timeout() {
          return Optional.ofNullable(t);
        }

        @Override
        public Optional<BodyPublisher> bodyPublisher() {
          return Optional.ofNullable(b);
        }

        @Override
        public HttpHeaders headers() {
          return h;
        }
      };
    }
  }
}
