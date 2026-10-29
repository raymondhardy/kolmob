package net.sourceforge.kolmafia;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.sourceforge.kolmafia.scripts.git.GitManager;

/**
 * Global vs per-user script projects.
 *
 * <p>KoLmafia installs script projects (Git/SVN) into one shared data root. Kolmob lets a project be
 * "global" (always installed) or owned by one account: a user-scoped project's files are synced into
 * the data root only while its owner is logged in, and removed again on logout or when another
 * account logs in. The project folders themselves stay in git/ and svn/. State is in
 * kolmob/projects.json: id ("git/&lt;folder&gt;" or "svn/&lt;folder&gt;") -> {scope, owner, synced}.
 */
final class KolProjects {
  private KolProjects() {}

  static final class Project {
    final String id;
    final String kind;
    final String name;
    final Path path;
    String scope = "global";
    String owner = "";
    boolean synced = true;

    Project(String id, String kind, String name, Path path) {
      this.id = id;
      this.kind = kind;
      this.name = name;
      this.path = path;
    }
  }

  static String norm(String user) {
    return user == null ? "" : user.trim().toLowerCase().replace(' ', '_');
  }

  private static File registryFile() {
    return new File(KoLConstants.ROOT_LOCATION, "kolmob/projects.json");
  }

  private static JSONObject loadRegistry() {
    try {
      File f = registryFile();
      if (f.exists()) {
        return JSON.parseObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
      }
    } catch (Exception ignored) {
      // a corrupt registry just means "everything global"
    }
    return new JSONObject();
  }

  private static void saveRegistry(List<Project> projects) {
    JSONObject reg = new JSONObject();
    for (Project p : projects) {
      if (p.scope.equals("global") && p.synced) continue; // the default state needs no entry
      JSONObject e = new JSONObject();
      e.put("scope", p.scope);
      e.put("owner", p.owner);
      e.put("synced", p.synced);
      reg.put(p.id, e);
    }
    try {
      File f = registryFile();
      f.getParentFile().mkdirs();
      Files.write(f.toPath(), reg.toJSONString().getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      RequestLogger.printLine("Could not save project scopes: " + e);
    }
  }

  /** Every installed project, with its saved scope. */
  static List<Project> list() {
    JSONObject reg = loadRegistry();
    List<Project> out = new ArrayList<>();
    for (String[] kd : new String[][] {{"git"}, {"svn"}}) {
      File root = kd[0].equals("git") ? KoLConstants.GIT_LOCATION : KoLConstants.SVN_LOCATION;
      File[] dirs = root.listFiles(File::isDirectory);
      if (dirs == null) continue;
      java.util.Arrays.sort(dirs);
      for (File d : dirs) {
        if (d.getName().endsWith(".updating")) continue;
        Project p = new Project(kd[0] + "/" + d.getName(), kd[0], d.getName(), d.toPath());
        JSONObject e = reg.getJSONObject(p.id);
        if (e != null) {
          p.scope = e.getString("scope") == null ? "global" : e.getString("scope");
          p.owner = e.getString("owner") == null ? "" : e.getString("owner");
          p.synced = e.getBooleanValue("synced", true);
        }
        out.add(p);
      }
    }
    return out;
  }

  private static boolean wanted(Project p, String activeUser) {
    return p.scope.equals("global") || (!activeUser.isEmpty() && norm(p.owner).equals(norm(activeUser)));
  }

  /** Make the data root match the active account: sync what it should have, remove what it should not. */
  static void reconcile(String activeUser) {
    List<Project> all = list();
    String user = activeUser == null ? "" : activeUser;
    // A project is only usable if what it depends on is installed too. A dependency that belongs to another
    // account (or was never synced for this one) would leave the project with missing imports, so make
    // such dependencies global.
    boolean promoted = false;
    for (Project p : all) {
      if (!p.kind.equals("git") || !wanted(p, user)) continue;
      for (String dep : GitManager.dependencyFolders(p.path)) {
        for (Project d : all) {
          if (d.kind.equals("git") && d.name.equals(dep) && !wanted(d, user)) {
            RequestLogger.printLine(
                d.name + " is needed by " + p.name + "; making it available to every account");
            d.scope = "global";
            d.owner = "";
            promoted = true;
          }
        }
      }
    }
    if (promoted) saveRegistry(all);
    Set<Path> removed = new HashSet<>();
    boolean changed = false;
    for (Project p : all) {
      boolean want = wanted(p, user);
      if (want == p.synced) continue;
      try {
        if (want) {
          RequestLogger.printLine("Installing " + p.name + " for " + user);
          GitManager.sync(p.path);
        } else {
          RequestLogger.printLine("Removing " + p.name + " (only for " + p.owner + ")");
          removed.addAll(GitManager.projectFiles(p.path));
          GitManager.unsyncProject(p.path);
        }
        p.synced = want;
        changed = true;
      } catch (IOException | RuntimeException e) {
        RequestLogger.printLine("Could not " + (want ? "install " : "remove ") + p.name + ": " + e);
      }
    }
    // files that two projects provided: put the survivor's copy back after removing the other's
    if (!removed.isEmpty()) {
      for (Project p : all) {
        if (!p.synced) continue;
        try {
          Set<Path> mine = GitManager.projectFiles(p.path);
          if (mine.stream().anyMatch(removed::contains)) GitManager.sync(p.path);
        } catch (IOException | RuntimeException ignored) {
          // best effort
        }
      }
    }
    if (changed) saveRegistry(all);
  }

  /** Give every project that appeared since {@code before} this scope (owner = the active account). */
  static int registerNew(Set<String> before, String scope, String owner) {
    List<Project> all = list();
    int n = 0;
    for (Project p : all) {
      if (before.contains(p.id)) continue;
      p.scope = scope.equals("user") ? "user" : "global";
      p.owner = p.scope.equals("user") ? owner : "";
      p.synced = true; // KoLmafia just synced it
      n++;
    }
    saveRegistry(all);
    return n;
  }

  static Set<String> ids() {
    Set<String> s = new HashSet<>();
    for (Project p : list()) s.add(p.id);
    return s;
  }

  /** Change one project's scope; the data root is reconciled for the active account. */
  static boolean setScope(String id, String scope, String owner, String activeUser) {
    List<Project> all = list();
    boolean found = false;
    for (Project p : all) {
      if (!p.id.equals(id)) continue;
      found = true;
      p.scope = scope.equals("user") ? "user" : "global";
      p.owner = p.scope.equals("user") ? owner : "";
    }
    if (found) saveRegistry(all);
    reconcile(activeUser);
    return found;
  }

  static String json() {
    JSONObject unused = null;
    StringBuilder sb = new StringBuilder("[");
    boolean first = true;
    for (Project p : list()) {
      if (!first) sb.append(',');
      first = false;
      String info = "";
      try {
        File meta = new File(p.path.toFile(), p.kind.equals("git") ? ".kolmob-git.json" : ".kolmob-svn.json");
        if (meta.exists()) {
          JSONObject m = JSON.parseObject(new String(Files.readAllBytes(meta.toPath()), StandardCharsets.UTF_8));
          info = p.kind.equals("git")
              ? m.getString("branch") + " @ " + (m.getString("commit") == null ? "" : m.getString("commit").substring(0, Math.min(7, m.getString("commit").length())))
              : "revision " + m.getString("revision");
        }
      } catch (Exception ignored) {
        // info is optional
      }
      Map<String, Object> o = new LinkedHashMap<>();
      o.put("id", p.id);
      o.put("kind", p.kind);
      o.put("name", p.name);
      o.put("scope", p.scope);
      o.put("owner", p.owner);
      o.put("synced", p.synced);
      o.put("info", info);
      sb.append(JSON.toJSONString(o));
    }
    return sb.append(']').toString();
  }
}
