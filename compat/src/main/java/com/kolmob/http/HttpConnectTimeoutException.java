package com.kolmob.http;

/** Android replacement for java.net.http.HttpConnectTimeoutException. */
public class HttpConnectTimeoutException extends HttpTimeoutException {
  public HttpConnectTimeoutException(String message) {
    super(message);
  }
}
