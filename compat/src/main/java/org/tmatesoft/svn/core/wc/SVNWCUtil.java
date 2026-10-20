package org.tmatesoft.svn.core.wc;

import java.io.File;
import org.tmatesoft.svn.core.SVNException;

/** Android shim: SVNKit is not available. */
public class SVNWCUtil {
  public static boolean isWorkingCopyRoot(File dir) throws SVNException {
    return false;
  }
}
