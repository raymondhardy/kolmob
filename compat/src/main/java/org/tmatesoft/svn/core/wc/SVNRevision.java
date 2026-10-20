package org.tmatesoft.svn.core.wc;

/** Android shim: SVNKit is not available. */
public class SVNRevision {
  public static final SVNRevision HEAD = new SVNRevision(-1);
  private final long number;

  public SVNRevision(long number) {
    this.number = number;
  }

  public long getNumber() {
    return number;
  }
}
