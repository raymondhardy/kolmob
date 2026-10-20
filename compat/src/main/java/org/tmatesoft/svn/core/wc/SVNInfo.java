package org.tmatesoft.svn.core.wc;

import java.util.Date;
import org.tmatesoft.svn.core.SVNURL;

/** Android shim: SVNKit is not available. Values come from Kolmob's own project metadata. */
public class SVNInfo {
  private SVNURL url;
  private long revision;
  private String author = "";
  private Date date = new Date(0);

  public static SVNInfo of(SVNURL url, long revision, String author, Date date) {
    SVNInfo i = new SVNInfo();
    i.url = url;
    i.revision = revision;
    i.author = author;
    i.date = date;
    return i;
  }

  public SVNURL getURL() {
    return url;
  }

  public SVNRevision getRevision() {
    return new SVNRevision(revision);
  }

  public String getAuthor() {
    return author;
  }

  public SVNRevision getCommittedRevision() {
    return new SVNRevision(revision);
  }

  public Date getCommittedDate() {
    return date;
  }
}
