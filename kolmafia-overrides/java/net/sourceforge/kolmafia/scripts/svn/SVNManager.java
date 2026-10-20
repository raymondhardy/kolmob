package net.sourceforge.kolmafia.scripts.svn;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.xml.parsers.DocumentBuilderFactory;
import net.sourceforge.kolmafia.KoLConstants;
import net.sourceforge.kolmafia.KoLmafia;
import net.sourceforge.kolmafia.RequestLogger;
import net.sourceforge.kolmafia.KoLConstants.MafiaState;
import net.sourceforge.kolmafia.scripts.ScriptManager;
import net.sourceforge.kolmafia.scripts.git.GitManager;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.wc.SVNInfo;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Android replacement for KoLmafia's SVNKit-based SVNManager.
 *
 * <p>SVNKit is too heavy for Android, so a "checkout" is an export over the repository's public
 * WebDAV interface (PROPFIND to list, GET to download) with OkHttp. Files are stored in
 * svn/&lt;id&gt; and installed into the data root by the same sync code Git projects use. There is
 * no working copy: "update" re-exports when the remote revision changed, and per-revision
 * rollback (svn inc/dec) is not supported.
 */
public class SVNManager extends ScriptManager {
  private static final String META = ".kolmob-svn.json";
  private static final String UA = "KoLmafia (Kolmob for Android)";
  private static final MediaType XML = MediaType.get("text/xml; charset=utf-8");
  private static final String PROPFIND_BODY =
      "<?xml version=\"1.0\" encoding=\"utf-8\"?><propfind xmlns=\"DAV:\"><prop>"
          + "<resourcetype xmlns=\"DAV:\"/><version-name xmlns=\"DAV:\"/></prop></propfind>";
  private static final OkHttpClient HTTP =
      new OkHttpClient.Builder()
          .connectTimeout(10, TimeUnit.SECONDS)
          .readTimeout(120, TimeUnit.SECONDS)
          .followRedirects(true)
          .build();

  /** Network calls on mobile connections fail transiently; retry with a fresh connection and backoff. */
  private static Response send(Request req) throws IOException {
    IOException last = null;
    for (int attempt = 0; attempt < 4; attempt++) {
      try {
        Response r = HTTP.newCall(req).execute();
        if (r.code() >= 500) {
          r.close();
          throw new IOException("HTTP " + r.code() + " from " + req.url());
        }
        return r;
      } catch (IOException e) {
        last = e;
        try {
          Thread.sleep(1000L << attempt);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw new IOException("Interrupted", ie);
        }
      }
    }
    throw last;
  }

  private SVNManager() {}

  private record Entry(String path, boolean dir, long rev) {}

  private static Path svnRoot() {
    return KoLConstants.SVN_LOCATION.toPath();
  }

  // ---- WebDAV ----

  private static List<Entry> propfind(String origin, String path, int depth) throws IOException {
    Request req =
        new Request.Builder()
            .url(origin + path)
            .header("Depth", String.valueOf(depth))
            .header("User-Agent", UA)
            .method("PROPFIND", RequestBody.create(PROPFIND_BODY, XML))
            .build();
    try (Response r = send(req)) {
      if (r.code() != 207) throw new IOException("HTTP " + r.code() + " listing " + origin + path);
      byte[] bytes = r.body().bytes();
      var dbf = DocumentBuilderFactory.newInstance();
      dbf.setNamespaceAware(true);
      var doc = dbf.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(bytes));
      NodeList responses = doc.getElementsByTagNameNS("DAV:", "response");
      List<Entry> out = new ArrayList<>();
      for (int i = 0; i < responses.getLength(); i++) {
        Element el = (Element) responses.item(i);
        String href = text(el, "href");
        if (href == null) continue;
        boolean dir = el.getElementsByTagNameNS("DAV:", "collection").getLength() > 0;
        long rev = 0;
        try {
          rev = Long.parseLong(text(el, "version-name"));
        } catch (RuntimeException ignored) {
          // not every server reports it
        }
        out.add(new Entry(href, dir, rev));
      }
      return out;
    } catch (IOException e) {
      throw e;
    } catch (Exception e) {
      throw new IOException("Could not read repository listing: " + e, e);
    }
  }

  private static String text(Element el, String local) {
    NodeList n = el.getElementsByTagNameNS("DAV:", local);
    if (n.getLength() == 0) return null;
    Node c = n.item(0);
    return c.getTextContent() == null ? null : c.getTextContent().trim();
  }

  private static String decodedPath(String href) {
    try {
      return new URI(href).getPath();
    } catch (Exception e) {
      return href;
    }
  }

  private static String lastSegment(String path) {
    String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    return p.substring(p.lastIndexOf('/') + 1);
  }

  private static long export(String origin, String path, Path dest) throws IOException {
    Files.createDirectories(dest);
    long maxRev = 0;
    String self = decodedPath(path.endsWith("/") ? path : path + "/");
    for (Entry e : propfind(origin, path, 1)) {
      String dp = decodedPath(e.path());
      maxRev = Math.max(maxRev, e.rev());
      if ((dp.endsWith("/") ? dp : dp + "/").equals(self)) continue; // the folder itself
      String name = lastSegment(dp);
      if (name.isEmpty() || name.equals(".svn") || name.contains("..")) continue;
      Path out = dest.resolve(name).normalize();
      if (!out.startsWith(dest.normalize())) throw new IOException("Unsafe path in repository: " + dp);
      if (e.dir()) {
        maxRev = Math.max(maxRev, export(origin, e.path(), out));
      } else {
        KoLmafia.updateDisplay("Downloading: " + name);
        Request get = new Request.Builder().url(origin + e.path()).header("User-Agent", UA).build();
        try (Response r = send(get)) {
          if (!r.isSuccessful()) throw new IOException("HTTP " + r.code() + " for " + dp);
          Files.write(out, r.body().bytes());
        }
      }
    }
    return maxRev;
  }

  private static long remoteRevision(SVNURL repo) throws IOException {
    URI u = URI.create(repo.toString());
    String origin = u.getScheme() + "://" + u.getAuthority();
    long rev = 0;
    for (Entry e : propfind(origin, u.getRawPath(), 0)) rev = Math.max(rev, e.rev());
    return rev;
  }

  // ---- metadata ----

  private static Optional<JSONObject> readMeta(Path project) {
    try {
      return Optional.of(JSON.parseObject(new String(Files.readAllBytes(project.resolve(META)), StandardCharsets.UTF_8)));
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  private static void writeMeta(Path project, SVNURL repo, long rev) throws IOException {
    JSONObject m = new JSONObject();
    m.put("url", repo.toString());
    m.put("revision", rev);
    m.put("date", new Date().toInstant().toString());
    Files.write(project.resolve(META), m.toJSONString().getBytes(StandardCharsets.UTF_8));
  }

  // ---- API used by KoLmafia's commands and ASH functions ----

  public static void doCleanup() {
    RequestLogger.printLine("Nothing to clean up (Android exports have no working copy).");
  }

  public static void doCheckout(SVNURL repo) {
    String id = getFolderUUIDNoRemote(repo);
    Path project = svnRoot().resolve(id);
    if (Files.exists(project)) {
      KoLmafia.updateDisplay(
          MafiaState.ERROR, "Project " + id + " is already checked out. Use svn update, or delete it first.");
      return;
    }
    install(repo, id, project);
  }

  private static boolean install(SVNURL repo, String id, Path project) {
    try {
      URI u = URI.create(repo.toString());
      String origin = u.getScheme() + "://" + u.getAuthority();
      Path staging = svnRoot().resolve(id + ".updating");
      GitManager.deleteProjectTree(staging);
      KoLmafia.updateDisplay("Checking out " + repo + " ...");
      long rev = export(origin, u.getRawPath(), staging);
      RequestLogger.printLine("Downloaded revision " + rev + "; installing files...");
      writeMeta(staging, repo, rev);
      boolean ok = GitManager.replaceProject(project, staging);
      if (ok) KoLmafia.updateDisplay("Checked out project " + id + " at revision " + rev);
      else KoLmafia.updateDisplay(MafiaState.ERROR, "Installing the files of " + id + " failed (see messages above)");
      return ok;
    } catch (IOException | RuntimeException | Error e) {
      StackTraceElement[] st = e.getStackTrace();
      KoLmafia.updateDisplay(
          MafiaState.ERROR,
          "Could not check out " + repo + ": " + e + (st.length > 0 ? " at " + st[0] : "") + (st.length > 1 ? " <- " + st[1] : ""));
      return false;
    }
  }

  public static void doUpdate() {
    File[] projects = KoLConstants.SVN_LOCATION.listFiles(File::isDirectory);
    if (projects == null) return;
    for (File f : projects) {
      if (!f.getName().endsWith(".updating")) doUpdate(f.getName());
    }
  }

  public static void doUpdate(String p) {
    Path project = svnRoot().resolve(p);
    var meta = readMeta(project);
    if (meta.isEmpty()) {
      KoLmafia.updateDisplay(MafiaState.ERROR, "Project " + p + " has no Kolmob metadata; delete and check it out again.");
      return;
    }
    try {
      SVNURL repo = SVNURL.parseURIEncoded(meta.get().getString("url"));
      long local = meta.get().getLongValue("revision");
      long remote = remoteRevision(repo);
      if (remote != 0 && remote == local) {
        RequestLogger.printLine("Project " + p + " is already at revision " + local);
        return;
      }
      RequestLogger.printLine("Updating project " + p + " (" + local + " -> " + remote + ")");
      install(repo, p, project);
    } catch (Exception e) {
      KoLmafia.updateDisplay(MafiaState.ERROR, "Failed to update " + p + ": " + e);
    }
  }

  public static void doUpdate(SVNURL repo) {
    String id = getFolderUUIDNoRemote(repo);
    if (Files.isDirectory(svnRoot().resolve(id))) doUpdate(id);
    else KoLmafia.updateDisplay(MafiaState.ERROR, "Project " + id + " is not installed.");
  }

  public static SVNInfo doInfo(File projectFile) throws SVNException {
    var meta = readMeta(projectFile.toPath());
    if (meta.isEmpty()) throw new SVNException("Not an installed SVN project: " + projectFile);
    return SVNInfo.of(
        SVNURL.parseURIEncoded(meta.get().getString("url")),
        meta.get().getLongValue("revision"),
        "",
        Date.from(java.time.Instant.parse(meta.get().getString("date"))));
  }

  public static boolean WCAtHead(File f, boolean quiet) {
    var meta = readMeta(f.toPath());
    if (meta.isEmpty()) return false;
    try {
      long remote = remoteRevision(SVNURL.parseURIEncoded(meta.get().getString("url")));
      return remote == 0 || remote == meta.get().getLongValue("revision");
    } catch (Exception e) {
      return false;
    }
  }

  public static void deleteInstalledProject(String p) {
    deleteInstalledProject(new File(KoLConstants.SVN_LOCATION, p));
  }

  public static void deleteInstalledProject(final File project) {
    try {
      GitManager.removeProject(project.toPath());
      KoLmafia.updateDisplay("Deleted project " + project.getName());
    } catch (IOException e) {
      KoLmafia.updateDisplay(MafiaState.ERROR, "Failed to delete " + project.getName() + ": " + e);
    }
  }

  public static void incrementProject(String p, int amount) {
    RequestLogger.printLine("Moving to older or newer revisions is not supported on Android.");
  }

  public static void syncAll() {
    File[] projects = KoLConstants.SVN_LOCATION.listFiles(File::isDirectory);
    if (projects == null) return;
    for (File f : projects) {
      if (!f.getName().endsWith(".updating")) GitManager.sync(f.toPath());
    }
  }

  public static void error(SVNException e, String addMessage) {
    RequestLogger.printLine("SVN error: " + e.getMessage() + (addMessage == null ? "" : " " + addMessage));
  }

  public static String getFolderUUID(SVNURL repo) {
    return getFolderUUIDNoRemote(repo);
  }

  public static String getFolderUUIDNoRemote(SVNURL repo) {
    return getProjectIdentifier(repo.getHost(), repo.getPath());
  }

  public static SVNURL workingCopyToSVNURL(File WCDir) throws SVNException {
    var meta = readMeta(WCDir.toPath());
    if (meta.isEmpty()) throw new SVNException("Not an installed SVN project: " + WCDir);
    return SVNURL.parseURIEncoded(meta.get().getString("url"));
  }

  public static String getRepoId(String repoUrl) throws SVNException {
    return getFolderUUID(SVNURL.parseURIEncoded(repoUrl));
  }
}
