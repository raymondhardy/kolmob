package org.tmatesoft.svn.core;

/** Android shim: SVNKit is not available. */
public class SVNException extends Exception {
  public SVNException(String message) {
    super(message);
  }
}
