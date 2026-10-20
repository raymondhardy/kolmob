package com.kolmob.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Android replacement for the subset of java.net.http.HttpClient that KoLmafia uses, built on
 * HttpURLConnection (java.net.http does not exist on Android).
 */
public abstract class HttpClient implements AutoCloseable {
  protected HttpClient() {}

  public enum Redirect {
    NEVER,
    ALWAYS,
    NORMAL
  }

  public enum Version {
    HTTP_1_1,
    HTTP_2
  }

  public interface Builder {
    Builder followRedirects(Redirect policy);

    Builder connectTimeout(Duration duration);

    Builder version(Version version);

    HttpClient build();
  }

  public static Builder newBuilder() {
    return new BuilderImpl();
  }

  public abstract <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
      throws IOException, InterruptedException;

  @Override
  public void close() {}

  private static final class BuilderImpl implements Builder {
    private Redirect redirect = Redirect.NEVER;
    private Duration connectTimeout = Duration.ofSeconds(30);

    @Override
    public Builder followRedirects(Redirect policy) {
      this.redirect = policy;
      return this;
    }

    @Override
    public Builder connectTimeout(Duration duration) {
      this.connectTimeout = duration;
      return this;
    }

    @Override
    public Builder version(Version version) {
      return this;
    }

    @Override
    public HttpClient build() {
      return new UrlConnectionClient(redirect, connectTimeout);
    }
  }

  private static final class UrlConnectionClient extends HttpClient {
    private final Redirect redirect;
    private final Duration connectTimeout;

    UrlConnectionClient(Redirect redirect, Duration connectTimeout) {
      this.redirect = redirect;
      this.connectTimeout = connectTimeout;
    }

    /**
     * The phone's network drops out for seconds at a time (background restrictions, radio handover). A
     * DNS or connect failure means nothing reached the server, so it is safe to retry; scripts that run
     * for an hour should wait the blip out instead of failing a turn.
     */
    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
        throws IOException, InterruptedException {
      int attempt = 0;
      while (true) {
        try {
          return sendOnce(request, handler);
        } catch (java.net.UnknownHostException | java.net.NoRouteToHostException | java.net.ConnectException e) {
          if (++attempt >= 40) throw e;
          if (Boolean.getBoolean("kolmob.http.debug")) System.err.println("kolmob-http retry " + attempt + " after " + e);
          Thread.sleep(attempt < 5 ? 1500 : 3000);
        }
      }
    }

    private <T> HttpResponse<T> sendOnce(HttpRequest request, HttpResponse.BodyHandler<T> handler)
        throws IOException, InterruptedException {
      URI uri = request.uri();
      final long t0 = System.currentTimeMillis();
      HttpURLConnection c = (HttpURLConnection) uri.toURL().openConnection();
      try {
        c.setInstanceFollowRedirects(redirect != Redirect.NEVER);
        c.setConnectTimeout((int) Math.min(Integer.MAX_VALUE, connectTimeout.toMillis()));
        c.setReadTimeout((int) request.timeout().map(Duration::toMillis).orElse(120_000L).longValue());
        c.setRequestMethod(request.method());
        for (Map.Entry<String, List<String>> e : request.headers().map().entrySet()) {
          for (String v : e.getValue()) c.addRequestProperty(e.getKey(), v);
        }
        var body = request.bodyPublisher();
        if (body.isPresent()) {
          byte[] bytes = body.get().bytes();
          c.setDoOutput(true);
          c.setFixedLengthStreamingMode(bytes.length);
          try (OutputStream os = c.getOutputStream()) {
            os.write(bytes);
          }
        }
        final int code;
        try {
          code = c.getResponseCode();
        } catch (SocketTimeoutException e) {
          if (Boolean.getBoolean("kolmob.http.debug")) System.err.println("kolmob-http TIMEOUT " + uri + " after " + (System.currentTimeMillis() - t0) + " ms: " + e);
          String msg = String.valueOf(e.getMessage());
          if (msg.toLowerCase().contains("connect")) throw new HttpConnectTimeoutException(msg);
          throw new HttpTimeoutException(msg);
        }
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) in = InputStream.nullInputStream();
        final HttpHeaders headers = HttpHeaders.of(c.getHeaderFields());
        final T bodyObj = handler.apply(in);
        if (Boolean.getBoolean("kolmob.http.debug")) {
          System.err.println("kolmob-http " + request.method() + " " + uri + " -> " + code + " in " + (System.currentTimeMillis() - t0) + " ms");
        }
        final URI finalUri = uri;
        return new HttpResponse<T>() {
          @Override
          public int statusCode() {
            return code;
          }

          @Override
          public T body() {
            return bodyObj;
          }

          @Override
          public HttpHeaders headers() {
            return headers;
          }

          @Override
          public URI uri() {
            return finalUri;
          }

          @Override
          public HttpRequest request() {
            return request;
          }
        };
      } finally {
        // The body stream (if streaming) is closed by the caller; the connection is released by it.
      }
    }
  }
}
