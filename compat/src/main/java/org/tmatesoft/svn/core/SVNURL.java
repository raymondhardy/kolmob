package org.tmatesoft.svn.core;

import java.net.URI;

/** Android shim: SVNKit is not available. */
public class SVNURL {
  private final String url;

  private SVNURL(String url) {
    this.url = url;
  }

  public static SVNURL parseURIEncoded(String url) throws SVNException {
    if (url == null || url.isEmpty()) throw new SVNException("Invalid URL");
    try {
      URI u = URI.create(url);
      if (u.getHost() == null) throw new SVNException("Invalid URL: " + url);
    } catch (IllegalArgumentException e) {
      throw new SVNException("Invalid URL: " + url);
    }
    return new SVNURL(url);
  }

  public String getHost() {
    return URI.create(url).getHost();
  }

  public String getPath() {
    return URI.create(url).getPath();
  }

  @Override
  public String toString() {
    return url;
  }
}
