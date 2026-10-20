package net.sourceforge.kolmafia;

import java.io.File;
import java.nio.file.Files;

/** JVM check of global vs per-user script projects (no KoL account needed; uses a temp data root). */
public final class ScopeSmoke {
  static File zlib(File root) {
    return new File(root, "scripts/zlib.ash");
  }

  public static void main(String[] args) throws Exception {
    File root = Files.createTempDirectory("kolmob-scope").toFile();
    KolEngine.start(root, line -> {});
    KoLCharacter.reset("alice");
    System.out.println("user now: " + KolEngine.userName());

    KolEngine.installProject("https://svn.code.sf.net/p/zlib/code", "user");
    System.out.println("1) installed for alice only      -> zlib.ash present: " + zlib(root).exists());
    System.out.println("   projects: " + KolEngine.projects());

    KolProjects.reconcile("bob");
    System.out.println("2) bob logs in                   -> zlib.ash present: " + zlib(root).exists() + " (expected false)");

    KolProjects.reconcile("");
    System.out.println("3) nobody logged in              -> zlib.ash present: " + zlib(root).exists() + " (expected false)");

    KolProjects.reconcile("Alice");
    System.out.println("4) alice logs in again           -> zlib.ash present: " + zlib(root).exists() + " (expected true)");

    String id = KolProjects.list().get(0).id;
    KolEngine.setProjectScope(id, "global");
    KolProjects.reconcile("bob");
    System.out.println("5) made global, bob logs in      -> zlib.ash present: " + zlib(root).exists() + " (expected true)");

    KolEngine.setProjectScope(id, "user"); // owner = alice (the current user)
    KolProjects.reconcile("bob");
    System.out.println("6) back to alice-only, bob in    -> zlib.ash present: " + zlib(root).exists() + " (expected false)");
    System.out.println("   registry: " + new String(Files.readAllBytes(new File(root, "kolmob/projects.json").toPath())));
    System.exit(0);
  }
}
