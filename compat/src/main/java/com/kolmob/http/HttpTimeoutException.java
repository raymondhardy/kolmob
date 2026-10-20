package com.kolmob.http;

import java.io.IOException;

/** Android replacement for java.net.http.HttpTimeoutException. */
public class HttpTimeoutException extends IOException {
  public HttpTimeoutException(String message) {
    super(message);
  }
}
