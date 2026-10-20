package net.sourceforge.kolmafia;

import java.io.File;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.function.Consumer;
import net.sourceforge.kolmafia.preferences.Preferences;
import net.sourceforge.kolmafia.request.GenericRequest;
import net.sourceforge.kolmafia.request.LoginRequest;

/**
 * Headless entry point to the real KoLmafia core for the Android app. Lives in the
 * {@code net.sourceforge.kolmafia} package so it can reach package-private startup hooks.
 */
public final class KolEngine {
  private static boolean started = false;
  private static Consumer<String> output = s -> {};

  private KolEngine() {}

  /** Starts the engine once. {@code root} becomes KoLmafia's data root (settings, scripts, data). */
  private static final java.util.ArrayDeque<String> recentOutput = new java.util.ArrayDeque<>();

  public static synchronized void start(File root, Consumer<String> sink) {
    output =
        line -> {
          synchronized (recentOutput) {
            recentOutput.addLast(line);
            while (recentOutput.size() > 8) recentOutput.removeFirst();
          }
          sink.accept(line);
        };
    if (started) return;
    // Before any KoLmafia class loads: KoLmafiaCLI.DEFAULT_SHELL captures System.in when it initializes.
    System.setIn(KolStdin.INSTANCE);
    root.mkdirs();
    // Must happen before any KoLmafia class initializes (it reads these in static initializers).
    System.setProperty("java.awt.headless", "true");
    // Android ignores user.dir changes, so the core is patched (tools/apply_patches.py) to read kolmob.root.
    System.setProperty("kolmob.root", root.getAbsolutePath());
    System.setProperty("useCWDasROOT", "true");
    System.setProperty("user.dir", root.getAbsolutePath());
    System.setProperty("user.home", root.getAbsolutePath());

    StaticEntity.setGUIRequired(false);
    RequestLogger.openCustom(
        new PrintStream(
            new OutputStream() {
              private final StringBuilder line = new StringBuilder();

              @Override
              public void write(int b) {
                if (b == '\n') {
                  output.accept(line.toString());
                  line.setLength(0);
                } else if (b != '\r') {
                  line.append((char) b);
                }
              }
            },
            true,
            java.nio.charset.StandardCharsets.UTF_8));

    Preferences.setBoolean("useDevServer", false);
    Preferences.setBoolean("relayBrowserOnly", false);
    GenericRequest.setUserAgent();
    KoLConstants.IMAGE_LOCATION.mkdirs();
    KoLConstants.SCRIPT_LOCATION.mkdirs();
    output.accept(
        "engine root: "
            + net.java.dev.spellcast.utilities.UtilityConstants.ROOT_LOCATION
            + " (user.dir="
            + System.getProperty("user.dir")
            + ", useCWDasROOT="
            + System.getProperty("useCWDasROOT")
            + ")");
    started = true;
  }

  /**
   * Touches the heavy lazily-initialised databases (modifiers, consumables, concoctions) so the first
   * login or script does not pay for them. They take ~25 s on a debuggable Android build.
   */
  public static synchronized void warmUp() {
    long t0 = System.currentTimeMillis();
    try {
      KoLCharacter.getUserName(); // initialises KoLCharacter -> Modifiers -> ModifierDatabase
      net.sourceforge.kolmafia.persistence.ConsumablesDatabase.getFullness("seal tooth");
    } catch (Throwable t) {
      output.accept("warm-up failed: " + t);
    }
    output.accept("warm-up done in " + (System.currentTimeMillis() - t0) + " ms");
  }

  /**
   * Debug helper: copies a file inside KoLmafia's root folder (e.g. a patched script from data/ over the real one in
   * scripts/). Scripts cannot write to scripts/ themselves. Returns a status line.
   */
  /** Diet plan as JSON (see KolDiet). mpa <= 0 uses the valueOfAdventure preference. */
  public static String dietPlan(long mpa, boolean allowNpc, boolean nightcap) {
    return dietPlan("mpa=" + mpa + ";npc=" + (allowNpc ? 1 : 0) + ";night=" + (nightcap ? 1 : 0));
  }

  /** Options string: mpa=500;owned=1;npc=0;mall=0;craft=0;night=0;food=1;booze=1;spleen=1;sim=0;black=a|b */
  public static String dietPlan(String spec) {
    try {
      return KolDiet.plan(spec);
    } catch (Throwable t) {
      return "{\"ok\":false,\"error\":" + quote(String.valueOf(t)) + "}";
    }
  }

  public static String copyRootFile(String fromRel, String toRel) {
    try {
      java.io.File root = KoLConstants.ROOT_LOCATION.getCanonicalFile();
      java.io.File from = new java.io.File(root, fromRel).getCanonicalFile();
      java.io.File to = new java.io.File(root, toRel).getCanonicalFile();
      if (!from.getPath().startsWith(root.getPath()) || !to.getPath().startsWith(root.getPath())) {
        return "refused: outside root";
      }
      java.nio.file.Files.copy(from.toPath(), to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      return "copied " + from.length() + " bytes to " + toRel;
    } catch (Exception e) {
      return "copy failed: " + e;
    }
  }

  /** Runs one CLI command line (e.g. {@code ash print("hi");}) and blocks until it finishes. */
  public static void execute(String commandLine) {
    // The desktop shell clears the error/abort state before every typed line; without this, one failed command
    // makes executeLine skip every later command silently.
    KoLmafia.forceContinue();
    KoLmafiaCLI.DEFAULT_SHELL.executeLine(commandLine);
  }

  /** Logs in through KoLmafia's real LoginRequest (blocks until the login attempt finishes). */
  public static void login(String username, String password) {
    Preferences.setBoolean("saveStateActive", true);
    RequestThread.postRequest(new LoginRequest(username, password));
    // per-user script projects: install this account's, remove other accounts'
    if (isLoggedIn()) KolProjects.reconcile(userName());
  }

  /** Logs out and removes the logged-out account's own (per-user) script projects from the data root. */
  public static void logout() {
    KoLmafiaCLI.DEFAULT_SHELL.executeLine("logout");
    KolProjects.reconcile("");
  }

  // ---- script projects: global or per-account ----

  /** Installed Git/SVN projects as JSON: [{id,kind,name,scope(global|user),owner,synced,info}]. */
  public static String projects() {
    try {
      return KolProjects.json();
    } catch (RuntimeException e) {
      return "[]";
    }
  }

  /**
   * Installs a script project from a GitHub or SourceForge SVN URL. scope is "global" (everyone) or "user"
   * (only the account that is logged in now). Dependencies it pulls in get the same scope.
   */
  public static void installProject(String url, String scope) {
    java.util.Set<String> before = KolProjects.ids();
    String lower = url.toLowerCase();
    if (lower.contains("github.com")) execute("git checkout " + url);
    else if (lower.contains("svn")) execute("svn checkout " + url);
    else {
      output.accept("Only GitHub and SourceForge SVN URLs are supported.");
      return;
    }
    KolProjects.registerNew(before, scope, userName());
  }

  /** Changes a project's scope ("global" or "user" for the logged-in account). */
  public static boolean setProjectScope(String id, String scope) {
    return KolProjects.setScope(id, scope, userName(), userName());
  }

  public static boolean isLoggedIn() {
    return !KoLCharacter.getUserName().isEmpty();
  }

  public static String userName() {
    return KoLCharacter.getUserName();
  }

  // ---- state for the UI ----

  /** Starts KoLmafia's relay server (the "relay browser" proxy) and returns its port, or -1. */
  public static synchronized int startRelay() {
    try {
      net.sourceforge.kolmafia.webui.RelayServer.startThread();
      for (int i = 0; i < 50 && !net.sourceforge.kolmafia.webui.RelayServer.isRunning(); i++) Thread.sleep(100);
      return net.sourceforge.kolmafia.webui.RelayServer.isRunning() ? net.sourceforge.kolmafia.webui.RelayServer.getPort() : -1;
    } catch (Throwable t) {
      output.accept("relay failed: " + t);
      return -1;
    }
  }

  /** Image path under the KoL image server's itemimages/ folder ("" if unknown). */
  private static String itemImg(int itemId) {
    try {
      return net.sourceforge.kolmafia.persistence.ItemDatabase.getImage(itemId);
    } catch (Throwable t) {
      return "";
    }
  }

  private static String familiarImg(int familiarId) {
    try {
      String loc = net.sourceforge.kolmafia.persistence.FamiliarDatabase.getFamiliarImageLocation(familiarId);
      return loc == null ? "" : loc;
    } catch (Throwable t) {
      return "";
    }
  }

  private static void kv(StringBuilder sb, String k, Object v) {
    if (sb.length() > 1 && sb.charAt(sb.length() - 1) != '{') sb.append(',');
    sb.append('"').append(k).append("\":");
    if (v instanceof Number || v instanceof Boolean) sb.append(v);
    else sb.append(quote(String.valueOf(v)));
  }

  private static String quote(String s) {
    StringBuilder b = new StringBuilder("\"");
    for (char c : s.toCharArray()) {
      switch (c) {
        case '"' -> b.append("\\\"");
        case '\\' -> b.append("\\\\");
        case '\n' -> b.append("\\n");
        case '\r' -> b.append("\\r");
        case '\t' -> b.append("\\t");
        default -> {
          if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
          else b.append(c);
        }
      }
    }
    return b.append('"').toString();
  }


  /** Items in the inventory and known skills that restore HP (hp=true) or MP, via KoLmafia's restore data. */
  private static void appendRestores(StringBuilder sb, String key, boolean hp) {
    sb.append(",\"").append(key).append("\":[");
    boolean first = true;
    for (net.sourceforge.kolmafia.AdventureResult r : new java.util.ArrayList<>(KoLConstants.inventory)) {
      double avg = hp
          ? net.sourceforge.kolmafia.persistence.RestoresDatabase.getHPAverage(r.getName())
          : net.sourceforge.kolmafia.persistence.RestoresDatabase.getMPAverage(r.getName());
      if (avg <= 0) continue;
      if (!first) sb.append(',');
      first = false;
      sb.append('{');
      kv(sb, "kind", "item");
      kv(sb, "name", r.getName());
      kv(sb, "count", r.getCount());
        kv(sb, "img", itemImg(r.getItemId()));
      kv(sb, "avg", Math.round(avg));
      sb.append('}');
    }
    for (net.sourceforge.kolmafia.request.UseSkillRequest u : new java.util.ArrayList<>(KoLConstants.availableSkills)) {
      String name = u.getSkillName();
      double avg = hp
          ? net.sourceforge.kolmafia.persistence.RestoresDatabase.getHPAverage(name)
          : net.sourceforge.kolmafia.persistence.RestoresDatabase.getMPAverage(name);
      if (avg <= 0) continue;
      if (!first) sb.append(',');
      first = false;
      sb.append('{');
      kv(sb, "kind", "skill");
      kv(sb, "name", name);
      kv(sb, "avg", Math.round(avg));
      kv(sb, "mpCost", net.sourceforge.kolmafia.persistence.SkillDatabase.getMPConsumptionById(net.sourceforge.kolmafia.persistence.SkillDatabase.getSkillId(name)));
      sb.append('}');
    }
    sb.append(']');
  }


  // ---- KoL account "Desktop/Mobile Options" (server-side flags that change KoL's own layout) ----

  private static final String[] ACCOUNT_FLAGS = {
    "flag_compactchar", "flag_shortcharpane", "flag_compacteffects", "flag_slimhpmpdisplay"
  };

  /** Current KoL interface options as JSON: the four checkbox flags plus "menu" (fancy|compact|normal). */
  public static synchronized String interfaceOptions() {
    GenericRequest r = new GenericRequest("account.php?tab=interface");
    RequestThread.postRequest(r);
    String t = r.responseText == null ? "" : r.responseText;
    StringBuilder sb = new StringBuilder("{");
    for (String f : ACCOUNT_FLAGS) {
      java.util.regex.Matcher m = java.util.regex.Pattern.compile("<input[^>]*name=\"" + f + "\"[^>]*>").matcher(t);
      kv(sb, f, m.find() && m.group().contains("checked"));
    }
    String menu = "fancy";
    java.util.regex.Matcher mm = java.util.regex.Pattern.compile("<input[^>]*type=\"radio\"[^>]*name=\"menu\"[^>]*>").matcher(t);
    while (mm.find()) {
      if (mm.group().contains("checked")) {
        java.util.regex.Matcher v = java.util.regex.Pattern.compile("value=\"([a-z]+)\"").matcher(mm.group());
        if (v.find()) menu = v.group(1);
      }
    }
    kv(sb, "menu", menu);
    kv(sb, "ok", !t.isEmpty());
    return sb.append('}').toString();
  }

  /** Changes one KoL account interface option (same request KoL's own options page sends). */
  public static synchronized void setAccountOption(String action, String value) {
    GenericRequest r = new GenericRequest("account.php", true);
    r.addFormField("am", "1");
    r.addFormField("pwd", GenericRequest.passwordHash);
    r.addFormField("action", action);
    r.addFormField("value", value);
    r.addFormField("ajax", "1");
    RequestThread.postRequest(r);
  }


  /** Stomach/liver/spleen, familiars, castable skills, food/booze/spleen items and outfits. */
  private static void appendExtras(StringBuilder sb) {
    try {
      sb.append(",\"organs\":{");
      kv(sb, "fullness", KoLCharacter.getFullness());
      kv(sb, "fullnessLimit", KoLCharacter.getStomachCapacity());
      kv(sb, "drunk", KoLCharacter.getInebriety());
      kv(sb, "drunkLimit", KoLCharacter.getLiverCapacity());
      kv(sb, "spleen", KoLCharacter.getSpleenUse());
      kv(sb, "spleenLimit", KoLCharacter.getSpleenLimit());
      sb.append('}');
    } catch (Throwable t) {
      sb.append(",\"organsError\":").append(quote(String.valueOf(t)));
    }
    try {
      sb.append(",\"familiars\":[");
      boolean first = true;
      var current = KoLCharacter.getFamiliar();
      for (var f : new java.util.ArrayList<>(KoLCharacter.getFamiliarList())) {
        if (f == null || f.getId() <= 0) continue;
        if (!first) sb.append(',');
        first = false;
        sb.append('{');
        kv(sb, "race", f.getRace());
        kv(sb, "img", familiarImg(f.getId()));
        kv(sb, "name", f.getName());
        kv(sb, "weight", f.getWeight());
        kv(sb, "current", current != null && f.getId() == current.getId());
        sb.append('}');
      }
      sb.append(']');
    } catch (Throwable t) {
      sb.append(",\"familiarsError\":").append(quote(String.valueOf(t)));
    }
    try {
      sb.append(",\"skills\":[");
      boolean first = true;
      for (var u : new java.util.ArrayList<>(KoLConstants.availableSkills)) {
        int id = net.sourceforge.kolmafia.persistence.SkillDatabase.getSkillId(u.getSkillName());
        if (id <= 0 || net.sourceforge.kolmafia.persistence.SkillDatabase.isPassive(id)) continue;
        if (!net.sourceforge.kolmafia.persistence.SkillDatabase.isNonCombat(id)
            && !net.sourceforge.kolmafia.persistence.SkillDatabase.isBuff(id)) continue;
        if (!first) sb.append(',');
        first = false;
        sb.append('{');
        kv(sb, "name", u.getSkillName());
        kv(sb, "id", id);
        kv(sb, "mpCost", net.sourceforge.kolmafia.persistence.SkillDatabase.getMPConsumptionById(id));
        kv(sb, "buff", net.sourceforge.kolmafia.persistence.SkillDatabase.isBuff(id));
        kv(sb, "kind", (net.sourceforge.kolmafia.persistence.SkillDatabase.isSong(id) || (id / 1000 == 6 
            && net.sourceforge.kolmafia.persistence.SkillDatabase.isBuff(id))) ? "Songs"
            : net.sourceforge.kolmafia.persistence.SkillDatabase.isExpression(id) ? "Expressions"
            : net.sourceforge.kolmafia.persistence.SkillDatabase.isWalk(id) ? "Walks" : "");
        kv(sb, "perCast", net.sourceforge.kolmafia.persistence.SkillDatabase.getEffectDuration(id));
        sb.append('}');
      }
      sb.append(']');
    } catch (Throwable t) {
      sb.append(",\"skillsError\":").append(quote(String.valueOf(t)));
    }
    try {
      sb.append(",\"passives\":[");
      boolean firstP = true;
      for (var u : new java.util.ArrayList<>(KoLConstants.availableSkills)) {
        int id = net.sourceforge.kolmafia.persistence.SkillDatabase.getSkillId(u.getSkillName());
        if (id <= 0 || !net.sourceforge.kolmafia.persistence.SkillDatabase.isPassive(id)) continue;
        if (!firstP) sb.append(',');
        firstP = false;
        sb.append(quote(u.getSkillName()));
      }
      sb.append(']');
    } catch (Throwable t) {
      sb.append(",\"passivesError\":").append(quote(String.valueOf(t)));
    }
    try {
      appendConsumables(sb, "food", 0);
      appendConsumables(sb, "booze", 1);
      appendConsumables(sb, "spleenItems", 2);
    } catch (Throwable t) {
      sb.append(",\"consumablesError\":").append(quote(String.valueOf(t)));
    }
    try {
      sb.append(",\"outfits\":[");
      boolean first = true;
      for (var o : new java.util.ArrayList<>(net.sourceforge.kolmafia.session.EquipmentManager.getCustomOutfits())) {
        if (o == null) continue;
        if (!first) sb.append(',');
        first = false;
        sb.append(quote(o.getName()));
      }
      sb.append(']');
    } catch (Throwable t) {
      sb.append(",\"outfitsError\":").append(quote(String.valueOf(t)));
    }
  }

  private static void appendConsumables(StringBuilder sb, String key, int kind) {
    sb.append(",\"").append(key).append("\":[");
    boolean first = true;
    for (net.sourceforge.kolmafia.AdventureResult r : new java.util.ArrayList<>(KoLConstants.inventory)) {
      String name = r.getName();
      int size =
          kind == 0
              ? net.sourceforge.kolmafia.persistence.ConsumablesDatabase.getFullness(name)
              : kind == 1
                  ? net.sourceforge.kolmafia.persistence.ConsumablesDatabase.getInebriety(name)
                  : net.sourceforge.kolmafia.persistence.ConsumablesDatabase.getSpleenHit(name);
      if (size <= 0) continue;
      if (!first) sb.append(',');
      first = false;
      sb.append('{');
      kv(sb, "name", name);
      kv(sb, "count", r.getCount());
        kv(sb, "img", itemImg(r.getItemId()));
      kv(sb, "size", size);
      try {
        kv(sb, "adv", net.sourceforge.kolmafia.persistence.ConsumablesDatabase.getAverageAdventures(name));
        var q = net.sourceforge.kolmafia.persistence.ConsumablesDatabase.getQuality(name);
        kv(sb, "quality", q == null ? "" : q.toString());
      } catch (Throwable ignored) {
      }
      sb.append('}');
    }
    sb.append(']');
  }


  // ---- adventuring ----

  /** Every adventure location as JSON: [{"name","zone","id","ok"}] (ok = KoLmafia says it can adventure there now). */
  public static String locations() {
    StringBuilder sb = new StringBuilder("[");
    boolean first = true;
    try {
      for (net.sourceforge.kolmafia.KoLAdventure a :
          new java.util.ArrayList<>(net.sourceforge.kolmafia.persistence.AdventureDatabase.getAsLockableListModel())) {
        if (a == null) continue;
        if (!first) sb.append(',');
        first = false;
        sb.append('{');
        kv(sb, "name", a.getAdventureName());
        String zone = a.getParentZoneDescription();
        kv(sb, "zone", zone == null || zone.isEmpty() ? a.getZone() : zone);
        kv(sb, "id", a.getAdventureId());
        boolean ok = true;
        try {
          ok = a.canAdventure();
        } catch (RuntimeException ignored) {
          // unknown prerequisites: still listed
        }
        kv(sb, "ok", ok);
        sb.append('}');
      }
    } catch (RuntimeException e) {
      output.accept("locations failed: " + e);
    }
    return sb.append(']').toString();
  }

  /** Sets a preference immediately, even while a script is running (e.g. autoscend's auto_interrupt). */
  /** Chat highlight words: the preference is "word\ncolour" pairs; applies to messages that arrive afterwards. */
  public static synchronized void setHighlights(String list) {
    net.sourceforge.kolmafia.preferences.Preferences.setString("highlightList", list);
    net.sourceforge.kolmafia.chat.StyledChatBuffer.initializeHighlights();
  }

  public static String getPref(String name) {
    try {
      return net.sourceforge.kolmafia.preferences.Preferences.getString(name);
    } catch (Throwable t) {
      return "";
    }
  }

  public static void setPref(String name, String value) {
    Preferences.setString(name, value);
  }

  /** Cancels whatever is running (like pressing the stop button in KoLmafia). Safe from any thread. */
  public static void abort() {
    RequestThread.declareWorldPeace();
    KolStdin.INSTANCE.feed(" "); // also releases a script that is waiting for an answer (a blank line would be ignored)
  }

  /**
   * Called (on the engine thread) when a script waits for an answer (user_confirm, user_prompt, ...). The text
   * is the kind (confirm|alert|input) on the first line, then the last few lines the script printed (the question).
   * Answer with {@link #answerPrompt}.
   */
  public static void setPromptListener(java.util.function.Consumer<String> listener) {
    KoLmafiaCLI.DEFAULT_SHELL.startInputRetriever();
    KoLmafiaCLI.promptHook =
        message -> {
          // What kind of question is it? The caller tells us: confirm() = yes/no, alert() = just OK, else text.
          String kind = "input";
          for (StackTraceElement f : new Throwable().getStackTrace()) {
            String m = f.getMethodName();
            if (m.equals("confirm")) { kind = "confirm"; break; }
            if (m.equals("alert")) { kind = "alert"; break; }
            if (m.equals("input")) { kind = "input"; break; }
          }
          StringBuilder sb = new StringBuilder(kind).append('\n');
          synchronized (recentOutput) {
            for (String l : recentOutput) sb.append(l).append('\n');
          }
          listener.accept(sb.toString().trim());
        };
  }

  /** Answers a waiting prompt: "y"/"n" for confirmations, text for questions, " " (a space) for "no answer". */
  public static void answerPrompt(String line) {
    KolStdin.INSTANCE.feed(line);
  }

  /** Closet, storage, display case items and meat as JSON. */
  public static synchronized String items() {
    StringBuilder sb = new StringBuilder("{");
    try {
      // Closet items
      sb.append("\"closet\":[");
      boolean first = true;
      for (net.sourceforge.kolmafia.AdventureResult r : new java.util.ArrayList<>(KoLConstants.closet)) {
        if (!first) sb.append(',');
        first = false;
        sb.append('{');
        kv(sb, "name", r.getName());
        kv(sb, "count", r.getCount());
        kv(sb, "img", itemImg(r.getItemId()));
        sb.append('}');
      }
      sb.append("]");
      // Storage items
      sb.append(",\"storage\":[");
      first = true;
      for (net.sourceforge.kolmafia.AdventureResult r : new java.util.ArrayList<>(KoLConstants.storage)) {
        if (!first) sb.append(',');
        first = false;
        sb.append('{');
        kv(sb, "name", r.getName());
        kv(sb, "count", r.getCount());
        kv(sb, "img", itemImg(r.getItemId()));
        sb.append('}');
      }
      sb.append("]");
      // Display case (collection) items
      sb.append(",\"display\":[");
      first = true;
      for (net.sourceforge.kolmafia.AdventureResult r : new java.util.ArrayList<>(KoLConstants.collection)) {
        if (!first) sb.append(',');
        first = false;
        sb.append('{');
        kv(sb, "name", r.getName());
        kv(sb, "count", r.getCount());
        kv(sb, "img", itemImg(r.getItemId()));
        sb.append('}');
      }
      sb.append("]");
      // Meat in closet and storage
      sb.append(",\"closetMeat\":").append(KoLCharacter.getClosetMeat());
      sb.append(",\"storageMeat\":").append(KoLCharacter.getStorageMeat());
    } catch (Throwable t) {
      sb.append(",\"error\":").append(quote(String.valueOf(t)));
    }
    return sb.append('}').toString();
  }

  /** Adventure-related state: turns, last location, combat settings and available combat scripts. */
  public static String adventureState() {
    StringBuilder sb = new StringBuilder("{");
    try {
      kv(sb, "loggedIn", isLoggedIn());
      kv(sb, "adventures", KoLCharacter.getAdventuresLeft());
      kv(sb, "lastAdventure", Preferences.getString("lastAdventure"));
      kv(sb, "battleAction", Preferences.getString("battleAction"));
      kv(sb, "ccs", Preferences.getString("customCombatScript"));
      kv(sb, "mood", Preferences.getString("currentMood"));
      try {
        kv(sb, "round", net.sourceforge.kolmafia.request.FightRequest.getCurrentRound());
        String mn = net.sourceforge.kolmafia.combat.MonsterStatusTracker.getLastMonsterName();
        kv(sb, "monster", mn == null ? "" : mn);
        if (mn != null && !mn.isEmpty()) {
          kv(sb, "monsterHp", net.sourceforge.kolmafia.combat.MonsterStatusTracker.getMonsterHealth());
          kv(sb, "monsterAtk", net.sourceforge.kolmafia.combat.MonsterStatusTracker.getMonsterAttack());
          kv(sb, "monsterDef", net.sourceforge.kolmafia.combat.MonsterStatusTracker.getMonsterDefense());
          kv(sb, "monsterPhylum", String.valueOf(net.sourceforge.kolmafia.combat.MonsterStatusTracker.getMonsterPhylum()));
        }
      } catch (Throwable ignored) {
      }
      kv(sb, "hp", KoLCharacter.getCurrentHP());
      kv(sb, "maxHp", KoLCharacter.getMaximumHP());
      sb.append(",\"ccsFiles\":[");
      java.io.File[] files = KoLConstants.CCS_LOCATION.listFiles((d, n) -> n.endsWith(".ccs") || n.endsWith(".txt"));
      boolean first = true;
      if (files != null) {
        java.util.Arrays.sort(files);
        for (java.io.File f : files) {
          if (!first) sb.append(',');
          first = false;
          String n = f.getName();
          sb.append(quote(n.substring(0, n.lastIndexOf('.'))));
        }
      }
      sb.append(']');
    } catch (Throwable t) {
      sb.append(",\"error\":").append(quote(String.valueOf(t)));
    }
    return sb.append('}').toString();
  }

  /** Mall search: JSON [{name,img,price,quantity,limit,shop,shopId}] cheapest first (up to max rows). */
  public static synchronized String mallSearch(String query, int max) {
    StringBuilder sb = new StringBuilder("{\"results\":[");
    try {
      java.util.List<net.sourceforge.kolmafia.request.PurchaseRequest> results = new java.util.ArrayList<>();
      net.sourceforge.kolmafia.request.MallSearchRequest req =
          new net.sourceforge.kolmafia.request.MallSearchRequest(query, max, results);
      req.run();
      boolean first = true;
      int n = 0;
      for (net.sourceforge.kolmafia.request.PurchaseRequest p : new java.util.ArrayList<>(results)) {
        if (p == null) continue;
        if (n++ >= max) break;
        if (!first) sb.append(',');
        first = false;
        sb.append('{');
        kv(sb, "name", p.getItemName());
        kv(sb, "img", itemImg(p.getItemId()));
        kv(sb, "price", p.getPrice());
        kv(sb, "quantity", p.getQuantity());
        kv(sb, "limit", p.getLimit());
        kv(sb, "shop", p.getShopName());
        sb.append('}');
      }
      sb.append(']');
    } catch (Throwable t) {
      sb.append("],\"error\":").append(quote(String.valueOf(t)));
    }
    return sb.append('}').toString();
  }

  /** NPC stores you can buy from now: JSON {shops:[{name,items:[{name,img,price,limit}]}]}. */
  public static synchronized String npcShops() {
    StringBuilder sb = new StringBuilder("{\"shops\":[");
    try {
      java.util.TreeMap<String, java.util.List<String>> byShop = new java.util.TreeMap<>();
      for (var e : new java.util.ArrayList<>(net.sourceforge.kolmafia.persistence.ItemDatabase.entrySet())) {
        int id = e.getKey();
        for (var r : net.sourceforge.kolmafia.persistence.NPCStoreDatabase.getAvailablePurchaseRequests(id)) {
          StringBuilder it = new StringBuilder("{");
          kv(it, "name", r.getItemName());
          kv(it, "img", itemImg(id));
          kv(it, "price", r.getPrice());
          kv(it, "limit", r.getLimit());
          it.append('}');
          byShop.computeIfAbsent(r.getShopName(), k -> new java.util.ArrayList<>()).add(it.toString());
        }
      }
      boolean first = true;
      for (var e : byShop.entrySet()) {
        if (!first) sb.append(',');
        first = false;
        sb.append("{\"name\":").append(quote(e.getKey())).append(",\"items\":[").append(String.join(",", e.getValue())).append("]}");
      }
      sb.append(']');
    } catch (Throwable t) {
      sb.append("],\"error\":").append(quote(String.valueOf(t)));
    }
    return sb.append('}').toString();
  }

  /** Coinmasters: JSON {shops:[{name,nick,zone,open,reason,tokens,items:[{name,img,cost}]}]}. */
  public static synchronized String coinmasters() {
    StringBuilder sb = new StringBuilder("{\"shops\":[");
    boolean first = true;
    for (var d : net.sourceforge.kolmafia.CoinmasterRegistry.COINMASTERS) {
      try {
        StringBuilder it = new StringBuilder();
        int n = 0;
        if (d.getShopRows() != null) {
          for (var row : new java.util.ArrayList<>(d.getShopRows())) {
            var item = row.getItem();
            if (item == null || !item.isItem()) continue;
            if (n++ > 0) it.append(',');
            it.append('{');
            kv(it, "name", item.getName());
            kv(it, "img", itemImg(item.getItemId()));
            kv(it, "cost", row.costString());
            it.append('}');
          }
        } else if (d.getBuyItems() != null) {
          for (var item : new java.util.ArrayList<>(d.getBuyItems())) {
            if (n++ > 0) it.append(',');
            it.append('{');
            kv(it, "name", item.getName());
            kv(it, "img", itemImg(item.getItemId()));
            kv(it, "cost", d.getBuyPrice(item.getItemId()) + " " + d.getToken());
            it.append('}');
          }
        }
        if (n == 0) { net.sourceforge.kolmafia.RequestLogger.printLine("coinmaster empty: " + d.getMaster() + " rows=" + d.getShopRows() + " buy=" + d.getBuyItems()); continue; }
        String reason = null;
        try { reason = d.accessible(); } catch (Throwable ignored) { }
        if (!first) sb.append(',');
        first = false;
        sb.append('{');
        kv(sb, "name", d.getMaster());
        kv(sb, "nick", d.getNickname());
        kv(sb, "zone", d.getZone() == null ? "" : d.getZone());
        kv(sb, "open", reason == null);
        kv(sb, "reason", reason == null ? "" : reason);
        String tok = "";
        try { tok = d.availableTokens() + " " + (d.getToken() != null ? d.getToken() : d.getItem() != null ? d.getItem().getName() : ""); } catch (Throwable ignored) { }
        kv(sb, "tokens", tok);
        sb.append(",\"items\":[").append(it).append("]}");
      } catch (Throwable t) {
        net.sourceforge.kolmafia.RequestLogger.printLine("coinmaster " + d + ": " + t);
      }
    }
    return sb.append("],\"total\":").append(net.sourceforge.kolmafia.CoinmasterRegistry.COINMASTERS.length).append("}").toString();
  }

  /** Clan: JSON {id,name,stash:[{name,img,count}]}; loads the stash from KoL. */
  public static synchronized String clanInfo() {
    StringBuilder sb = new StringBuilder("{");
    try {
      kv(sb, "id", net.sourceforge.kolmafia.session.ClanManager.getClanId());
      kv(sb, "name", net.sourceforge.kolmafia.session.ClanManager.getClanName(true));
      var stash = net.sourceforge.kolmafia.session.ClanManager.getStash();
      sb.append(",\"stash\":[");
      boolean first = true;
      for (var r : new java.util.ArrayList<>(stash)) {
        if (!first) sb.append(',');
        first = false;
        sb.append('{');
        kv(sb, "name", r.getName());
        kv(sb, "img", itemImg(r.getItemId()));
        kv(sb, "count", r.getCount());
        sb.append('}');
      }
      sb.append(']');
    } catch (Throwable t) {
      sb.append(",\"stash\":[],\"error\":").append(quote(String.valueOf(t)));
    }
    return sb.append('}').toString();
  }

  /** Faxable monsters from the faxbots: JSON {monsters:[{name,command,category,bot}]}. */
  public static synchronized String faxMonsters() {
    StringBuilder sb = new StringBuilder("{\"monsters\":[");
    try {
      net.sourceforge.kolmafia.persistence.FaxBotDatabase.configure();
      java.util.Set<String> seen = new java.util.HashSet<>();
      boolean first = true;
      for (var bot : net.sourceforge.kolmafia.persistence.FaxBotDatabase.getSortedFaxbots()) {
        if (bot == null || bot.getName() == null) continue;
        for (var m : new java.util.ArrayList<>(bot.monsters)) {
          if (!seen.add(m.getCommand())) continue;
          if (!first) sb.append(',');
          first = false;
          sb.append('{');
          kv(sb, "name", m.getName());
          kv(sb, "command", m.getCommand());
          kv(sb, "category", m.getCategory() == null ? "" : m.getCategory());
          kv(sb, "bot", bot.getName());
          sb.append('}');
        }
      }
      sb.append(']');
    } catch (Throwable t) {
      sb.append("],\"error\":").append(quote(String.valueOf(t)));
    }
    return sb.append('}').toString();
  }

  /** CLI command names starting with prefix (lowercase), up to max, shortest first. */
  /** ASH library function signatures (e.g. print_html(string)) whose name starts with [prefix], newline separated. */
  public static String ashNames(String prefix, int max) {
    java.util.Set<String> out0 = new java.util.LinkedHashSet<>();
    try {
      String p = prefix.toLowerCase();
      for (net.sourceforge.kolmafia.textui.parsetree.Function f :
          net.sourceforge.kolmafia.textui.RuntimeLibrary.getFunctions()) {
        String n = f.getName();
        if (n != null && n.toLowerCase().startsWith(p) && n.matches("[A-Za-z0-9_]+")) out0.add(f.getSignature());
      }
    } catch (Throwable ignored) {
    }
    java.util.List<String> out = new java.util.ArrayList<>(out0);
    out.sort(java.util.Comparator.comparingInt(String::length).thenComparing(x -> x));
    return String.join("\n", out.subList(0, Math.min(max, out.size())));
  }

  /**
   * Parses ASH text with KoLmafia's own parser (nothing is run). Returns one line per problem:
   * "line\tchar\tSEVERITY\tmessage" (1-based line), or an empty string when the script parses cleanly.
   */
  public static String checkAsh(String path, String text) {
    StringBuilder sb = new StringBuilder();
    try {
      java.io.File file = new java.io.File(path);
      net.sourceforge.kolmafia.textui.Parser parser =
          new net.sourceforge.kolmafia.textui.Parser(
              file, new java.io.ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)), null);
      try {
        parser.parse();
      } catch (Throwable t) {
        if (parser.getDiagnostics().isEmpty()) sb.append("1\t1\tERROR\t").append(String.valueOf(t.getMessage()).replace('\n', ' ')).append('\n');
      }
      String[] srcLines = text.split("\n", -1);
      java.util.regex.Pattern pos = java.util.regex.Pattern.compile("^(.*) \\([^()]*?line (\\d+), char (\\d+)[^()]*\\)(.*)$", java.util.regex.Pattern.DOTALL);
      for (Object d : parser.getDiagnostics()) {
        String str = d.toString().replace('\n', ' ');
        java.util.regex.Matcher m = pos.matcher(str);
        String sev = str.contains("Warning") ? "WARNING" : "ERROR";
        if (m.matches()) {
          int line = Integer.parseInt(m.group(2));
          int col = Integer.parseInt(m.group(3));
          String msg = m.group(1) + m.group(4);
          // "Expected ;, found X": the parser notices at the next token, but the mistake is where the previous line ends
          if (msg.startsWith("Expected ") && line > 1 && line <= srcLines.length) {
            String cur = srcLines[line - 1];
            if (cur.substring(0, Math.min(Math.max(col - 1, 0), cur.length())).trim().isEmpty()) {
              int prev = line - 2;
              while (prev > 0 && srcLines[prev].trim().isEmpty()) prev--;
              if (!srcLines[prev].trim().isEmpty()) {
                line = prev + 1;
                col = srcLines[prev].length() + 1;
              }
            }
          }
          sb.append(line).append('\t').append(col).append('\t').append(sev).append('\t').append(msg).append('\n');
        }
        else sb.append("1\t1\t").append(sev).append('\t').append(str).append('\n');
        if (sb.length() > 4000) break;
      }
    } catch (Throwable t) {
      return "";
    }
    return sb.toString();
  }

  public static String commandNames(String prefix, int max) {
    java.util.Set<String> out0 = new java.util.LinkedHashSet<>();
    try {
      for (String k : new java.util.ArrayList<>(net.sourceforge.kolmafia.textui.command.AbstractCommand.lookup.keySet())) {
        if (k.startsWith("*") || !k.startsWith(prefix) || !k.matches("[a-z0-9_]+")) continue;
        out0.add(k);
      }
    } catch (Throwable ignored) {
    }
    java.util.List<String> out = new java.util.ArrayList<>(out0);
    out.sort(java.util.Comparator.comparingInt(String::length).thenComparing(x -> x));
    return String.join("\n", out.subList(0, Math.min(max, out.size())));
  }

  /** Player profile: JSON {name,id,class,level,clan,hardcore,ronin,pvp,ascensions,turns,created,lastLogin,rank,title} or {error}. */
  public static synchronized String profile(String name) {
    StringBuilder sb = new StringBuilder("{");
    try {
      var r = new net.sourceforge.kolmafia.request.ProfileRequest(name);
      r.initialize();
      kv(sb, "name", r.getPlayerName());
      kv(sb, "id", r.getPlayerId());
      kv(sb, "class", String.valueOf(r.getClassType()));
      kv(sb, "level", r.getPlayerLevel());
      kv(sb, "clan", String.valueOf(r.getClanName()));
      kv(sb, "title", String.valueOf(r.getTitle()));
      kv(sb, "rank", String.valueOf(r.getRank()));
      kv(sb, "hardcore", r.isHardcore());
      kv(sb, "ronin", r.inRonin());
      kv(sb, "pvp", r.getPvpRank());
      kv(sb, "ascensions", r.getAscensionCount());
      kv(sb, "turns", r.getTurnsPlayed());
      kv(sb, "created", String.valueOf(r.getCreationAsString()));
      kv(sb, "lastLogin", String.valueOf(r.getLastLoginAsString()));
    } catch (Throwable t) {
      if (sb.length() <= 1) sb.append("\"x\":0,");
      sb.append("\"error\":").append(quote(String.valueOf(t)));
    }
    return sb.append('}').toString();
  }

  /** Your current numeric modifiers (non-zero): JSON {mods:[{name,value}]}. */
  public static synchronized String modifiers() {
    StringBuilder sb = new StringBuilder("{\"stats\":[");
    try {

      Object[][] st = {
        {"Muscle", net.sourceforge.kolmafia.KoLCharacter.getAdjustedMuscle(), net.sourceforge.kolmafia.KoLCharacter.getBaseMuscle(), net.sourceforge.kolmafia.KoLCharacter.getTotalMuscle()},
        {"Mysticality", net.sourceforge.kolmafia.KoLCharacter.getAdjustedMysticality(), net.sourceforge.kolmafia.KoLCharacter.getBaseMysticality(), net.sourceforge.kolmafia.KoLCharacter.getTotalMysticality()},
        {"Moxie", net.sourceforge.kolmafia.KoLCharacter.getAdjustedMoxie(), net.sourceforge.kolmafia.KoLCharacter.getBaseMoxie(), net.sourceforge.kolmafia.KoLCharacter.getTotalMoxie()},
      };
      for (int i = 0; i < st.length; i++) {
        if (i > 0) sb.append(',');
        sb.append('{');
        kv(sb, "name", st[i][0]);
        kv(sb, "buffed", st[i][1]);
        kv(sb, "base", st[i][2]);
        kv(sb, "substats", st[i][3]);
        sb.append('}');
      }
    } catch (Throwable ignored) {
    }
    sb.append("],\"mods\":[");
    try {
      var cur = net.sourceforge.kolmafia.KoLCharacter.getCurrentModifiers();
      boolean first = true;
      for (var m : net.sourceforge.kolmafia.modifiers.DoubleModifier.values()) {
        double v;
        try { v = cur.getDouble(m); } catch (Throwable t) { continue; }
        if (v == 0.0 || Double.isNaN(v)) continue;
        if (!first) sb.append(',');
        first = false;
        sb.append('{');
        kv(sb, "name", m.getName());
        kv(sb, "value", v);
        sb.append('}');
      }
      sb.append(']');
    } catch (Throwable t) {
      sb.append("],\"error\":").append(quote(String.valueOf(t)));
    }
    return sb.append('}').toString();
  }

  /** Mushroom plot: JSON {owns, current, forecast} as monospace text. Fetches the plot from KoL. */
  public static synchronized String mushroom() {
    StringBuilder sb = new StringBuilder("{");
    try {
      boolean owns = net.sourceforge.kolmafia.session.MushroomManager.ownsPlot();
      String cur = net.sourceforge.kolmafia.session.MushroomManager.getMushroomManager(true);
      String fore = net.sourceforge.kolmafia.session.MushroomManager.getForecastedPlot(true);
      kv(sb, "owns", owns);
      kv(sb, "current", cur == null ? "" : cur);
      kv(sb, "forecast", fore == null ? "" : fore);
    } catch (Throwable t) {
      kv(sb, "error", String.valueOf(t));
    }
    return sb.append('}').toString();
  }

  /** Toggles an item on KoLmafia's keep ("mementos") list. Returns "on", "off" or an error. */
  public static synchronized String toggleMemento(String name) { return toggleList(name, false); }

  /** Toggles an item on the junk list (used by cleanup). Returns "on", "off" or an error. */
  public static synchronized String toggleJunk(String name) { return toggleList(name, true); }

  public static synchronized boolean isJunk(String name) {
    try {
      int id = net.sourceforge.kolmafia.persistence.ItemDatabase.getItemId(name);
      return id > 0 && net.sourceforge.kolmafia.KoLConstants.junkList.contains(net.sourceforge.kolmafia.objectpool.ItemPool.get(id));
    } catch (Throwable t) {
      return false;
    }
  }

  private static String toggleList(String name, boolean junk) {
    try {
      int id = net.sourceforge.kolmafia.persistence.ItemDatabase.getItemId(name);
      if (id <= 0) return "unknown item";
      var item = net.sourceforge.kolmafia.objectpool.ItemPool.get(id);
      var list = junk ? net.sourceforge.kolmafia.KoLConstants.junkList : net.sourceforge.kolmafia.KoLConstants.mementoList;
      boolean on;
      if (list.contains(item)) { list.remove(item); on = false; }
      else { list.add(item); on = true; }
      if (!junk) net.sourceforge.kolmafia.preferences.Preferences.setBoolean("mementoListActive", true);
      net.sourceforge.kolmafia.persistence.FlaggedItems.saveFlaggedItemList();
      return on ? "on" : "off";
    } catch (Throwable t) {
      return String.valueOf(t);
    }
  }

  public static synchronized boolean isMemento(String name) {
    try {
      int id = net.sourceforge.kolmafia.persistence.ItemDatabase.getItemId(name);
      return id > 0 && net.sourceforge.kolmafia.KoLConstants.mementoList.contains(net.sourceforge.kolmafia.objectpool.ItemPool.get(id));
    } catch (Throwable t) {
      return false;
    }
  }

  /** Skills usable in combat: JSON array of {name,mpCost}. */
  public static synchronized String combatSkills() {
    StringBuilder sb = new StringBuilder("[");
    try {
      boolean first = true;
      for (var u : new java.util.ArrayList<>(KoLConstants.availableSkills)) {
        int id = net.sourceforge.kolmafia.persistence.SkillDatabase.getSkillId(u.getSkillName());
        if (id <= 0 || !net.sourceforge.kolmafia.persistence.SkillDatabase.isCombat(id)) continue;
        if (!first) sb.append(',');
        first = false;
        sb.append('{');
        kv(sb, "name", u.getSkillName());
        kv(sb, "mpCost", net.sourceforge.kolmafia.persistence.SkillDatabase.getMPConsumptionById(id));
        sb.append('}');
      }
    } catch (Throwable t) {
      // return what we have
    }
    return sb.append(']').toString();
  }

  /** Public buffbots from KoLmafia's list: JSON {bots:[name]}. Fetches current prices from the bots' sites. */
  /** Everything you own that fits a gear slot, wherever it is kept: JSON {items:[{name,where,count,can,req,power,img}]}. */
  public static synchronized String gearAll(String slotCli) {
    StringBuilder sb = new StringBuilder("{\"items\":[");
    try {
      net.sourceforge.kolmafia.equipment.Slot want;
      switch (slotCli) {
        case "hat": want = net.sourceforge.kolmafia.equipment.Slot.HAT; break;
        case "weapon": want = net.sourceforge.kolmafia.equipment.Slot.WEAPON; break;
        case "off-hand": want = net.sourceforge.kolmafia.equipment.Slot.OFFHAND; break;
        case "back": want = net.sourceforge.kolmafia.equipment.Slot.CONTAINER; break;
        case "shirt": want = net.sourceforge.kolmafia.equipment.Slot.SHIRT; break;
        case "pants": want = net.sourceforge.kolmafia.equipment.Slot.PANTS; break;
        case "familiar": want = net.sourceforge.kolmafia.equipment.Slot.FAMILIAR; break;
        default: want = net.sourceforge.kolmafia.equipment.Slot.ACCESSORY1; break;
      }
      String[] where = {"inventory", "closet", "storage"};
      java.util.List<java.util.List<net.sourceforge.kolmafia.AdventureResult>> lists = java.util.List.of(
          new java.util.ArrayList<>(net.sourceforge.kolmafia.KoLConstants.inventory),
          new java.util.ArrayList<>(net.sourceforge.kolmafia.KoLConstants.closet),
          new java.util.ArrayList<>(net.sourceforge.kolmafia.KoLConstants.storage));
      boolean first = true;
      for (int w = 0; w < 3; w++) {
        for (net.sourceforge.kolmafia.AdventureResult r : lists.get(w)) {
          if (r == null || r.getItemId() <= 0) continue;
          net.sourceforge.kolmafia.equipment.Slot t = net.sourceforge.kolmafia.session.EquipmentManager.itemIdToEquipmentType(r.getItemId());
          if (t != want) continue;
          if (!first) sb.append(',');
          first = false;
          sb.append('{');
          kv(sb, "name", r.getName());
          kv(sb, "where", where[w]);
          kv(sb, "count", r.getCount());
          kv(sb, "can", net.sourceforge.kolmafia.session.EquipmentManager.canEquip(r.getItemId()));
          String req = "";
          int power = 0;
          try {
            req = net.sourceforge.kolmafia.persistence.EquipmentDatabase.getEquipRequirement(r.getItemId());
            power = net.sourceforge.kolmafia.persistence.EquipmentDatabase.getPower(r.getItemId());
          } catch (Throwable ignored) {
          }
          kv(sb, "req", req == null ? "" : req);
          kv(sb, "power", power);
          kv(sb, "img", itemImg(r.getItemId()));
          sb.append('}');
        }
      }
      sb.append(']');
    } catch (Throwable t) {
      sb.append("],\"error\":").append(quote(String.valueOf(t)));
    }
    return sb.append('}').toString();
  }

  /** Cake-Shaped Arena status: JSON {familiar,name,weight,trainable,opponents:[{name,race,weight}]}. */
  public static synchronized String arenaInfo() {
    StringBuilder sb = new StringBuilder("{");
    try {
      var fam = net.sourceforge.kolmafia.KoLCharacter.getFamiliar();
      boolean none = fam == null || fam == net.sourceforge.kolmafia.FamiliarData.NO_FAMILIAR;
      kv(sb, "familiar", none ? "" : fam.getRace());
      kv(sb, "name", none ? "" : fam.getName());
      kv(sb, "weight", none ? 0 : fam.getWeight());
      kv(sb, "trainable", !none && fam.trainable());
      sb.append(",\"opponents\":[");
      boolean first = true;
      for (var o : new java.util.ArrayList<>(net.sourceforge.kolmafia.CakeArenaManager.getOpponentList())) {
        if (!first) sb.append(',');
        first = false;
        sb.append('{');
        kv(sb, "name", o.getName());
        kv(sb, "race", o.getRace());
        kv(sb, "weight", o.getWeight());
        sb.append('}');
      }
      sb.append(']');
    } catch (Throwable t) {
      sb.append("\"error\":").append(quote(String.valueOf(t)));
    }
    return sb.append('}').toString();
  }

  /** How many of KoLmafia's stop conditions are still unmet (met ones are removed as the loop runs). */
  public static synchronized String conditionsLeft() {
    try {
      return String.valueOf(new java.util.ArrayList<>(net.sourceforge.kolmafia.session.GoalManager.getGoals()).size());
    } catch (Throwable t) {
      return "-1";
    }
  }

  public static synchronized String buffRequestBots() {
    StringBuilder sb = new StringBuilder("{\"bots\":[");
    try {
      if (net.sourceforge.kolmafia.persistence.BuffBotDatabase.hasOfferings()) {
        boolean first = true;
        for (String b : net.sourceforge.kolmafia.persistence.BuffBotDatabase.getCompleteBotList()) {
          if (b == null || b.isEmpty()) continue;
          if (!first) sb.append(',');
          first = false;
          sb.append(quote(b));
        }
      }
      sb.append(']');
    } catch (Throwable t) {
      sb.append("],\"error\":").append(quote(String.valueOf(t)));
    }
    return sb.append('}').toString();
  }

  /** What one buffbot sells: JSON {offers:[{price,text,free}]}. */
  public static synchronized String buffRequestOffers(String bot) {
    StringBuilder sb = new StringBuilder("{\"offers\":[");
    try {
      boolean first = true;
      for (int pass = 0; pass < 2; pass++) {
        var list = pass == 0
            ? net.sourceforge.kolmafia.persistence.BuffBotDatabase.getStandardOfferings(bot)
            : net.sourceforge.kolmafia.persistence.BuffBotDatabase.getPhilanthropicOfferings(bot);
        for (var o : new java.util.ArrayList<>(list)) {
          if (!first) sb.append(',');
          first = false;
          sb.append('{');
          kv(sb, "price", o.getPrice());
          kv(sb, "text", o.toString());
          kv(sb, "free", pass == 1);
          sb.append('}');
        }
      }
      sb.append(']');
    } catch (Throwable t) {
      sb.append("],\"error\":").append(quote(String.valueOf(t)));
    }
    return sb.append('}').toString();
  }

  /** BuffBot price list: JSON {offers:[{price,text}]}. */
  public static synchronized String buffbotOffers() {
    StringBuilder sb = new StringBuilder("{\"offers\":[");
    try {
      net.sourceforge.kolmafia.session.BuffBotManager.loadSettings();
      boolean first = true;
      for (var o : new java.util.ArrayList<>(net.sourceforge.kolmafia.session.BuffBotManager.getBuffCostTable())) {
        if (!first) sb.append(',');
        first = false;
        sb.append('{');
        kv(sb, "price", o.getPrice());
        kv(sb, "text", o.toString());
        sb.append('}');
      }
      sb.append(']');
    } catch (Throwable t) {
      sb.append("],\"error\":").append(quote(String.valueOf(t)));
    }
    return sb.append('}').toString();
  }

  public static synchronized String buffbotAdd(String skill, int price, int casts) {
    try {
      net.sourceforge.kolmafia.session.BuffBotManager.addBuff(skill, price, casts);
      return "ok";
    } catch (Throwable t) {
      return String.valueOf(t);
    }
  }

  public static synchronized String buffbotRemove(int price) {
    try {
      for (var o : new java.util.ArrayList<>(net.sourceforge.kolmafia.session.BuffBotManager.getBuffCostTable())) {
        if (o.getPrice() == price) {
          net.sourceforge.kolmafia.session.BuffBotManager.removeBuffs(java.util.List.of(o));
          return "ok";
        }
      }
      return "not found";
    } catch (Throwable t) {
      return String.valueOf(t);
    }
  }

  /** Session gains (KoLmafia's tally): JSON {gains:[{name,count,img,kind}]}; kind is item|meat|stat|effect. */
  public static synchronized String gains() {
    StringBuilder sb = new StringBuilder("{\"gains\":[");
    try {
      boolean first = true;
      for (var r : new java.util.ArrayList<>(net.sourceforge.kolmafia.KoLConstants.tally)) {
        if (r == null || r.getCount() == 0) continue;
        if (!first) sb.append(',');
        first = false;
        sb.append('{');
        kv(sb, "name", r.getName());
        kv(sb, "count", r.getCount());
        String kind = r.isItem() ? "item" : r.isMeat() ? "meat" : r.isStatusEffect() ? "effect" : "stat";
        kv(sb, "kind", kind);
        kv(sb, "img", r.isItem() ? itemImg(r.getItemId()) : "");
        sb.append('}');
      }
      sb.append(']');
    } catch (Throwable t) {
      sb.append("],\"error\":").append(quote(String.valueOf(t)));
    }
    return sb.append('}').toString();
  }

  /** Kmail inbox (newest first, up to max): JSON {messages:[{id,sender,time,html}]}. Fetches from KoL. */
  public static synchronized String inbox(int max) {
    StringBuilder sb = new StringBuilder("{\"messages\":[");
    try {
      new net.sourceforge.kolmafia.request.MailboxRequest("Inbox").run();
      var box = net.sourceforge.kolmafia.session.MailManager.getMessages("Inbox");
      java.util.List<net.sourceforge.kolmafia.KoLMailMessage> all = box == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(box);
      boolean first = true;
      int n = 0;
      for (var m : all) {
        if (n++ >= max) break;
        if (!first) sb.append(',');
        first = false;
        sb.append('{');
        kv(sb, "id", m.getMessageId());
        kv(sb, "sender", m.getSenderName());
        kv(sb, "time", String.valueOf(m.getTimestamp()));
        kv(sb, "html", m.getMessageHTML());
        sb.append('}');
      }
      sb.append(']');
    } catch (Throwable t) {
      sb.append("],\"error\":").append(quote(String.valueOf(t)));
    }
    return sb.append('}').toString();
  }

  /** Deletes one inbox message by id; returns "ok" or an error. */
  public static synchronized String deleteMail(String id) { return mailAction(id, false); }

  /** Moves one inbox message to the saved box. */
  public static synchronized String saveMail(String id) { return mailAction(id, true); }

  private static String mailAction(String id, boolean save) {
    try {
      var box = net.sourceforge.kolmafia.session.MailManager.getMessages("Inbox");
      if (box != null) {
        for (var m : new java.util.ArrayList<>(box)) {
          if (m.getMessageId().equals(id)) {
            if (save) net.sourceforge.kolmafia.session.MailManager.saveMessage("Inbox", m);
            else net.sourceforge.kolmafia.session.MailManager.deleteMessage("Inbox", m);
            return "ok";
          }
        }
      }
      return "not found";
    } catch (Throwable t) {
      return String.valueOf(t);
    }
  }

  /** Things you can make right now: JSON [{name,img,possible,method,ingredients}]. */
  public static synchronized String creatables() {
    StringBuilder sb = new StringBuilder("{\"items\":[");
    boolean first = true;
    try {
      net.sourceforge.kolmafia.persistence.ConcoctionDatabase.refreshConcoctionsNow();
      for (var r : new java.util.ArrayList<>(net.sourceforge.kolmafia.persistence.ConcoctionDatabase.getCreatables())) {
        if (r == null || r.getQuantityPossible() <= 0) continue;
        int id = r.getItemId();
        if (!first) sb.append(',');
        first = false;
        sb.append('{');
        kv(sb, "name", r.getName());
        kv(sb, "img", itemImg(id));
        kv(sb, "possible", r.getQuantityPossible());
        String method = "";
        StringBuilder ing = new StringBuilder();
        try {
          method = String.valueOf(net.sourceforge.kolmafia.persistence.ConcoctionDatabase.getMixingMethod(id));
          for (var a : net.sourceforge.kolmafia.persistence.ConcoctionDatabase.getIngredients(id)) {
            if (ing.length() > 0) ing.append(" + ");
            ing.append(a.getCount() > 1 ? a.getCount() + "× " : "").append(a.getName());
          }
        } catch (Throwable ignored) {
        }
        kv(sb, "method", method);
        kv(sb, "ingredients", ing.toString());
        sb.append('}');
      }
      sb.append(']');
    } catch (Throwable t) {
      sb.append("],\"error\":").append(quote(String.valueOf(t)));
    }
    return sb.append('}').toString();
  }

  /** Your mall store: JSON {open, items:[{name,img,quantity,price,limit,lowest}]}. Loads it from KoL first. */
  public static synchronized String storeItems() {
    StringBuilder sb = new StringBuilder("{");
    try {
      net.sourceforge.kolmafia.session.StoreManager.clearCache();
      new net.sourceforge.kolmafia.request.ManageStoreRequest().run();
      sb.append("\"items\":[");
      boolean first = true;
      for (var it : new java.util.ArrayList<>(net.sourceforge.kolmafia.session.StoreManager.getSoldItemList())) {
        if (!first) sb.append(',');
        first = false;
        sb.append('{');
        kv(sb, "name", it.getItemName());
        kv(sb, "img", itemImg(it.getItemId()));
        kv(sb, "quantity", it.getQuantity());
        kv(sb, "price", it.getPrice());
        kv(sb, "limit", it.getLimit());
        kv(sb, "lowest", it.getLowest());
        sb.append('}');
      }
      sb.append(']');
    } catch (Throwable t) {
      sb.append("\"items\":[],\"error\":").append(quote(String.valueOf(t)));
    }
    return sb.append('}').toString();
  }

  /** Auto-restore settings for HP or MP: JSON {threshold,target,items:[{name,on}]} (fractions 0..1; threshold < 0 means off). */
  public static synchronized String restoreConfig(boolean hp) {
    StringBuilder sb = new StringBuilder("{");
    try {
      String base = hp ? "hpAutoRecovery" : "mpAutoRecovery";
      kv(sb, "threshold", Preferences.getDouble(base));
      kv(sb, "target", Preferences.getDouble(base + "Target"));
      java.util.Set<String> on = new java.util.HashSet<>();
      for (String n : Preferences.getString(base + "Items").split(";")) on.add(n.trim().toLowerCase());
      sb.append(",\"items\":[");
      Object[] all = hp
          ? net.sourceforge.kolmafia.moods.HPRestoreItemList.CONFIGURES
          : net.sourceforge.kolmafia.moods.MPRestoreItemList.CONFIGURES;
      boolean first = true;
      for (Object o : all) {
        String name = String.valueOf(o);
        if (!first) sb.append(',');
        first = false;
        sb.append('{');
        kv(sb, "name", name);
        kv(sb, "on", on.contains(name.toLowerCase()));
        sb.append('}');
      }
      sb.append(']');
    } catch (Throwable t) {
      sb.append(",\"error\":").append(quote(String.valueOf(t)));
    }
    return sb.append('}').toString();
  }

  /** Quest log: JSON {helpers:{hedge,volcano}, quests:[{pref,title,status,text}]} for every quest that is started or finished. */
  public static synchronized String quests() {
    StringBuilder sb = new StringBuilder("{\"quests\":[");
    boolean first = true;
    String hedge = "", last = "";
    try {
      last = Preferences.getString("lastAdventure");
      hedge = Preferences.getString("questL13Final");
      String user = KoLCharacter.getUserName();
      try (java.io.BufferedReader reader =
          net.sourceforge.kolmafia.utilities.FileUtilities.getVersionedReader(
              "questslog.txt", KoLConstants.QUESTSLOG_VERSION)) {
        String[] data;
        while ((data = net.sourceforge.kolmafia.utilities.FileUtilities.readData(reader)) != null) {
          if (data.length < 3) continue;
          String status = Preferences.getString(data[0]);
          if (status == null || status.isEmpty() || status.equals("unstarted")) continue;
          int steps = data.length - 2;
          int idx;
          if (status.equals("started")) idx = 0;
          else if (status.equals("finished")) idx = steps - 1;
          else if (status.startsWith("step")) {
            try { idx = Integer.parseInt(status.substring(4)); } catch (NumberFormatException e) { idx = 0; }
          } else idx = 0;
          idx = Math.max(0, Math.min(idx, steps - 1));
          String text = data[2 + idx].replaceAll("<Player\\sName>", user).replaceAll("<[^>]*>", " ").trim();
          if (!first) sb.append(',');
          first = false;
          sb.append('{');
          kv(sb, "pref", data[0]);
          kv(sb, "title", data[1].replaceAll("<Player\\sName>", user));
          kv(sb, "status", status);
          kv(sb, "text", text);
          sb.append('}');
        }
      }
    } catch (Throwable t) {
      sb.append("],\"error\":").append(quote(String.valueOf(t))).append(",\"x\":[");
    }
    sb.append("],\"hedgeActive\":").append("step4".equals(hedge));
    sb.append(",\"volcanoActive\":").append(last.toLowerCase().contains("volcano"));
    return sb.append('}').toString();
  }

  /** Database lookup: returns JSON array [{id,name,summary}] for items/monsters/skills/effects matching query. */
  public static synchronized String lookup(String kind, String query) {
    StringBuilder sb = new StringBuilder("[");
    try {
      if (query == null || query.trim().isEmpty()) return sb.append(']').toString();
      String lowerQuery = query.trim().toLowerCase();
      boolean first = true;
      int count = 0;
      final int MAX_RESULTS = 50;

      switch (kind.toLowerCase()) {
        case "item" -> {
          for (var entry : net.sourceforge.kolmafia.persistence.ItemDatabase.entrySet()) {
            if (count >= MAX_RESULTS) break;
            int id = entry.getKey();
            var data = entry.getValue();
            if (data == null) continue;
            String name = net.sourceforge.kolmafia.persistence.ItemDatabase.getItemName(id);
            if (name == null || name.isEmpty()) continue;
            if (!name.toLowerCase().contains(lowerQuery)) continue;
            if (!first) sb.append(',');
            first = false;
            count++;
            String type = net.sourceforge.kolmafia.persistence.ItemDatabase.getConsumptionType(id).toString();
            String summary = "Type: " + type;
            sb.append('{');
            kv(sb, "id", id);
            kv(sb, "name", name);
            kv(sb, "summary", summary);
            sb.append('}');
          }
        }
        case "monster" -> {
          for (var entry : net.sourceforge.kolmafia.persistence.MonsterDatabase.entrySet()) {
            if (count >= MAX_RESULTS) break;
            String key = entry.getKey();
            var data = entry.getValue();
            if (data == null) continue;
            String name = data.getName();
            if (name == null || name.isEmpty()) continue;
            if (!name.toLowerCase().contains(lowerQuery)) continue;
            if (!first) sb.append(',');
            first = false;
            count++;
            int attack = data.getAttack();
            int defense = data.getDefense();
            int hp = data.getHP();
            var elem = data.getAttackElement();
            String element = elem != null ? elem.toString() : "none";
            String summary = "Atk: " + attack + ", Def: " + defense + ", HP: " + hp + ", Elem: " + element;
            // Use the numeric ID from MonsterData
            int id = data.getId();
            sb.append('{');
            kv(sb, "id", id);
            kv(sb, "name", name);
            kv(sb, "summary", summary);
            sb.append('}');
          }
        }
        case "skill" -> {
          for (var entry : net.sourceforge.kolmafia.persistence.SkillDatabase.entrySet()) {
            if (count >= MAX_RESULTS) break;
            int id = entry.getKey();
            var data = entry.getValue();
            if (data == null) continue;
            String name = data.name();
            if (name == null || name.isEmpty()) continue;
            if (!name.toLowerCase().contains(lowerQuery)) continue;
            if (!first) sb.append(',');
            first = false;
            count++;
            long mpCost = net.sourceforge.kolmafia.persistence.SkillDatabase.getMPConsumptionById(id);
            String summary = "MP cost: " + mpCost;
            sb.append('{');
            kv(sb, "id", id);
            kv(sb, "name", name);
            kv(sb, "summary", summary);
            sb.append('}');
          }
        }
        case "effect" -> {
          for (var entry : net.sourceforge.kolmafia.persistence.EffectDatabase.allEffects()) {
            if (count >= MAX_RESULTS) break;
            int id = entry.getKey();
            var data = entry.getValue();
            if (data == null) continue;
            String name = data.getName();
            if (name == null || name.isEmpty()) continue;
            if (!name.toLowerCase().contains(lowerQuery)) continue;
            if (!first) sb.append(',');
            first = false;
            count++;
            String action = "";
            try {
              String a = net.sourceforge.kolmafia.persistence.EffectDatabase.getDefaultAction(id);
              if (a != null) action = a;
            } catch (RuntimeException ignored) {}
            String summary = "Action: " + (action.isEmpty() ? "none" : action);
            sb.append('{');
            kv(sb, "id", id);
            kv(sb, "name", name);
            kv(sb, "summary", summary);
            sb.append('}');
          }
        }
      }
    } catch (Throwable t) {
      sb.append("{\"error\":").append(quote(String.valueOf(t))).append('}');
    }
    return sb.append(']').toString();
  }

  /** Database describe: returns longer multi-line text for a specific item/monster/skill/effect by ID. */
  public static synchronized String describe(String kind, int id) {
    StringBuilder sb = new StringBuilder();
    try {
      switch (kind.toLowerCase()) {
        case "item" -> {
          String name = net.sourceforge.kolmafia.persistence.ItemDatabase.getItemName(id);
          if (name == null) return "Item not found";
          sb.append(name).append("\n\n");
          String type = net.sourceforge.kolmafia.persistence.ItemDatabase.getConsumptionType(id).toString();
          sb.append("Type: ").append(type).append("\n");
          int price = net.sourceforge.kolmafia.persistence.ItemDatabase.getPriceById(id);
          if (price > 0) sb.append("Autosell: ").append(price).append(" meat\n");
        }
        case "monster" -> {
          String name = net.sourceforge.kolmafia.persistence.MonsterDatabase.getMonsterName(id);
          if (name == null || name.isEmpty()) return "Monster not found";
          sb.append(name).append("\n\n");
          // Find the monster data by iterating
          var data = net.sourceforge.kolmafia.persistence.MonsterDatabase.entrySet().stream()
              .map(java.util.Map.Entry::getValue)
              .filter(m -> m != null && m.getId() == id)
              .findFirst()
              .orElse(null);
          if (data == null) {
            sb.append("(ID: ").append(id).append(")");
          } else {
            sb.append(data.getName()).append("\n\n");
            sb.append("Attack: ").append(data.getAttack()).append("\n");
            sb.append("Defense: ").append(data.getDefense()).append("\n");
            sb.append("HP: ").append(data.getHP()).append("\n");
            var elem = data.getAttackElement();
            sb.append("Element: ").append(elem != null ? elem.toString() : "none").append("\n");
            sb.append("Phylum: ").append(data.getPhylum()).append("\n");
          }
        }
        case "skill" -> {
          String skillName = net.sourceforge.kolmafia.persistence.SkillDatabase.getSkillName(id);
          if (skillName == null || skillName.isEmpty()) return "Skill not found";
          var data = net.sourceforge.kolmafia.persistence.SkillDatabase.entrySet().stream()
              .map(java.util.Map.Entry::getValue)
              .filter(d -> d != null && d.name().equals(skillName))
              .findFirst()
              .orElse(null);
          if (data == null) return "Skill not found";
          sb.append(data.name()).append("\n\n");
          long mpCost = net.sourceforge.kolmafia.persistence.SkillDatabase.getMPConsumptionById(id);
          sb.append("MP cost: ").append(mpCost).append("\n");
          sb.append("Category: ").append(data.category()).append("\n");
          int duration = data.duration();
          if (duration > 0) sb.append("Duration: ").append(duration).append(" turns\n");
        }
        case "effect" -> {
          String name = net.sourceforge.kolmafia.persistence.EffectDatabase.getEffectName(id);
          if (name == null) return "Effect not found";
          sb.append(name).append("\n\n");
          String action = "";
          try {
            String a = net.sourceforge.kolmafia.persistence.EffectDatabase.getDefaultAction(id);
            if (a != null) action = a;
          } catch (RuntimeException ignored) {}
          sb.append("Default action: ").append(action.isEmpty() ? "none" : action).append("\n");
          var attrs = net.sourceforge.kolmafia.persistence.EffectDatabase.getEffectAttributes(id);
          if (attrs != null && !attrs.isEmpty()) {
            sb.append("Attributes: ").append(String.join(", ", attrs)).append("\n");
          }
        }
      }
    } catch (Throwable t) {
      sb.append("Error: ").append(t);
    }
    return sb.toString();
  }


  // ---- chat: KoLmafia's own chat pipeline (ChatPoller -> ChatManager buffers -> ChatSender) ----

  /** Starts KoLmafia's chat (polling thread, per-channel buffers). Needs the Altar of Literacy and a login. */
  public static synchronized boolean chatStart() {
    try {
      net.sourceforge.kolmafia.chat.ChatManager.initialize();
    } catch (Throwable t) {
      output.accept("chat failed to start: " + t);
    }
    return net.sourceforge.kolmafia.chat.ChatManager.isRunning();
  }

  /** {"running","literate","current","channels":[active channels],"buffers":[every open channel/whisper key]} */
  public static String chatState() {
    StringBuilder sb = new StringBuilder("{");
    try {
      kv(sb, "running", net.sourceforge.kolmafia.chat.ChatManager.isRunning());
      kv(sb, "literate", net.sourceforge.kolmafia.chat.ChatManager.chatLiterate());
      String cur = net.sourceforge.kolmafia.chat.ChatManager.getCurrentChannel();
      kv(sb, "current", cur == null ? "" : cur);
      sb.append(",\"channels\":[");
      boolean first = true;
      for (String c : new java.util.ArrayList<>(net.sourceforge.kolmafia.chat.ChatManager.activeChannels)) {
        if (!first) sb.append(',');
        first = false;
        sb.append(quote(c));
      }
      sb.append("],\"buffers\":[");
      first = true;
      for (String k : chatBufferKeys()) {
        if (!first) sb.append(',');
        first = false;
        sb.append(quote(k));
      }
      sb.append(']');
    } catch (Throwable t) {
      sb.append(",\"error\":").append(quote(String.valueOf(t)));
    }
    return sb.append('}').toString();
  }

  @SuppressWarnings("unchecked")
  private static java.util.List<String> chatBufferKeys() throws ReflectiveOperationException {
    var f = net.sourceforge.kolmafia.chat.ChatManager.class.getDeclaredField("instantMessageBuffers");
    f.setAccessible(true);
    var map = (java.util.Map<String, ?>) f.get(null);
    synchronized (map) {
      return new java.util.ArrayList<>(map.keySet());
    }
  }

  /** {"current":id,"name","options":[{"id","name"}]}: KoL's account auto-attack (also used by the browser) and the choices. */
  public static String autoAttackInfo() {
    StringBuilder sb = new StringBuilder("{");
    try {
      int aa = KoLCharacter.getAutoAttackAction();
      kv(sb, "current", aa);
      String name =
          aa == 0
              ? "Disabled"
              : aa == 1
                  ? "Attack with weapon"
                  : aa == 3
                      ? "Pick pocket"
                      : net.sourceforge.kolmafia.persistence.SkillDatabase.getSkillName(aa);
      kv(sb, "name", name == null ? "Skill " + aa : name);
      sb.append(",\"options\":[{\"id\":0,\"name\":\"Disabled\"},{\"id\":1,\"name\":\"Attack with weapon\"},{\"id\":3,\"name\":\"Pick pocket\"}");
      for (var r :
          net.sourceforge.kolmafia.persistence.SkillDatabase.getSkillsByType(
              net.sourceforge.kolmafia.persistence.SkillDatabase.SkillTag.COMBAT)) {
        int id = r.getSkillId();
        if (id <= 3 || !KoLCharacter.hasSkill(id)) continue;
        sb.append(",{\"id\":").append(id).append(",\"name\":").append(quote(r.getSkillName())).append('}');
      }
      sb.append(']');
    } catch (Throwable t) {
      sb.append(",\"error\":").append(quote(String.valueOf(t)));
    }
    return sb.append('}').toString();
  }

  /** JSON array of every configurable choice adventure: {choice,zone,name,setting,value,options:[{d,n}]}; value 0 = manual. */
  public static String choiceList() {
    StringBuilder sb = new StringBuilder("[");
    try {
      var list = new java.util.ArrayList<>(java.util.Arrays.asList(net.sourceforge.kolmafia.session.ChoiceAdventures.CHOICE_ADVS));
      list.sort(java.util.Comparator.comparing((net.sourceforge.kolmafia.session.ChoiceAdventures.ChoiceAdventure c) -> c.getZone()).thenComparing(c -> c));
      boolean first = true;
      for (var c : list) {
        if (!first) sb.append(',');
        first = false;
        sb.append("{\"choice\":").append(c.getChoice()).append(",\"zone\":").append(quote(c.getZone()))
            .append(",\"name\":").append(quote(c.getName())).append(",\"setting\":").append(quote(c.getSetting()))
            .append(",\"value\":").append(net.sourceforge.kolmafia.preferences.Preferences.getInteger(c.getSetting()))
            .append(",\"options\":[");
        var opts = c.getOptions();
        for (int i = 0; opts != null && i < opts.length; i++) {
          if (i > 0) sb.append(',');
          sb.append("{\"d\":").append(opts[i].getDecision(i + 1)).append(",\"n\":").append(quote(opts[i].getName())).append('}');
        }
        sb.append("]}");
      }
    } catch (Throwable t) {
      return "[]";
    }
    return sb.append(']').toString();
  }

  /** {"away":bool,"delay":ms}: the chat poller's current backoff (KoLmafia's away mode polls slower). */
  public static String chatPoll() {
    StringBuilder sb = new StringBuilder("{");
    kv(sb, "away", net.sourceforge.kolmafia.chat.ChatPoller.isPaused());
    int delay = 0;
    try {
      var inst = net.sourceforge.kolmafia.chat.ChatPoller.getInstance();
      if (inst != null) {
        var f = net.sourceforge.kolmafia.chat.ChatPoller.class.getDeclaredField("delay");
        f.setAccessible(true);
        delay = f.getInt(inst);
      }
    } catch (ReflectiveOperationException ignored) {
      // delay stays 0: the UI shows "connecting"
    }
    kv(sb, "delay", delay);
    return sb.append('}').toString();
  }

  /** {"running", "lens":{key:length}}: size of every open chat buffer (cheap unread detection). */
  public static String chatLengths() {
    StringBuilder sb = new StringBuilder("{");
    try {
      boolean running = net.sourceforge.kolmafia.chat.ChatManager.isRunning();
      kv(sb, "running", running);
      sb.append(",\"lens\":{");
      StringBuilder msgs = new StringBuilder();
      if (running) {
        boolean first = true;
        for (String k : chatBufferKeys()) {
          String html = net.sourceforge.kolmafia.chat.ChatManager.getBuffer(k).getHTMLContent();
          if (html == null) html = "";
          if (!first) { sb.append(','); msgs.append(','); }
          first = false;
          sb.append(quote(k)).append(':').append(html.length());
          int n = 0;
          for (int i = html.indexOf("<br"); i >= 0; i = html.indexOf("<br", i + 3)) n++;
          msgs.append(quote(k)).append(':').append(n);
        }
      }
      sb.append("},\"msgs\":{").append(msgs).append('}');
    } catch (Throwable t) {
      sb.append(",\"error\":").append(quote(String.valueOf(t)));
    }
    return sb.append('}').toString();
  }

  /** {"key","len","html"}: the buffer's HTML from character offset {@code from} (poll with the last len). */
  public static String chatText(String key, int from) {
    StringBuilder sb = new StringBuilder("{");
    try {
      String html = net.sourceforge.kolmafia.chat.ChatManager.getBuffer(key).getHTMLContent();
      if (html == null) html = "";
      int start = Math.max(0, Math.min(from, html.length()));
      kv(sb, "key", key);
      kv(sb, "len", html.length());
      int n = 0;
      for (int i = html.indexOf("<br"); i >= 0; i = html.indexOf("<br", i + 3)) n++;
      kv(sb, "msgs", n);
      kv(sb, "html", html.substring(start));
    } catch (Throwable t) {
      kv(sb, "error", String.valueOf(t));
    }
    return sb.append('}').toString();
  }

  /** Sends text to a channel ("/newbie") or whispers a player (name), exactly like KoLmafia's chat window. */
  public static void chatSend(String key, String text) {
    net.sourceforge.kolmafia.chat.ChatSender.sendMessage(key, text, false);
  }

  /** A JSON snapshot of the logged-in character for the UI (just {"loggedIn":false} when logged out). */
  public static synchronized String snapshot() {
    StringBuilder sb = new StringBuilder("{");
    try {
      kv(sb, "loggedIn", isLoggedIn());
      if (!isLoggedIn()) return sb.append('}').toString();
      kv(sb, "name", KoLCharacter.getUserName());
      kv(sb, "level", KoLCharacter.getLevel());
      kv(sb, "class", KoLCharacter.getAscensionClassName());
      kv(sb, "hp", KoLCharacter.getCurrentHP());
      kv(sb, "maxHp", KoLCharacter.getMaximumHP());
      kv(sb, "mp", KoLCharacter.getCurrentMP());
      kv(sb, "maxMp", KoLCharacter.getMaximumMP());
      kv(sb, "meat", KoLCharacter.getAvailableMeat());
      kv(sb, "adventures", KoLCharacter.getAdventuresLeft());
      kv(sb, "turnsPlayed", KoLCharacter.getTurnsPlayed());
      kv(sb, "familiar", KoLCharacter.getFamiliar() == null ? "" : KoLCharacter.getFamiliar().getRace());
      kv(sb, "path", String.valueOf(KoLCharacter.getPath()));
      kv(sb, "lastAdventure", Preferences.getString("lastAdventure"));
      sb.append(",\"inventory\":[");
      boolean first = true;
      for (net.sourceforge.kolmafia.AdventureResult r : new java.util.ArrayList<>(KoLConstants.inventory)) {
        if (!first) sb.append(',');
        first = false;
        sb.append('{');
        kv(sb, "id", r.getItemId());
        kv(sb, "name", r.getName());
        kv(sb, "count", r.getCount());
        kv(sb, "img", itemImg(r.getItemId()));
        sb.append('}');
      }
      sb.append("],\"effects\":[");
      first = true;
      for (net.sourceforge.kolmafia.AdventureResult r : new java.util.ArrayList<>(KoLConstants.activeEffects)) {
        if (!first) sb.append(',');
        first = false;
        sb.append('{');
        kv(sb, "name", r.getName());
        kv(sb, "turns", r.getCount());
        String action = "";
        try {
          String a = net.sourceforge.kolmafia.persistence.EffectDatabase.getDefaultAction(r.getEffectId());
          if (a != null) action = a;
        } catch (RuntimeException ignored) {
          // effects without a known source
        }
        kv(sb, "action", action);
        sb.append('}');
      }
      sb.append("],\"equipment\":[");
      net.sourceforge.kolmafia.equipment.Slot[] slots = {
        net.sourceforge.kolmafia.equipment.Slot.HAT,
        net.sourceforge.kolmafia.equipment.Slot.WEAPON,
        net.sourceforge.kolmafia.equipment.Slot.OFFHAND,
        net.sourceforge.kolmafia.equipment.Slot.CONTAINER,
        net.sourceforge.kolmafia.equipment.Slot.SHIRT,
        net.sourceforge.kolmafia.equipment.Slot.PANTS,
        net.sourceforge.kolmafia.equipment.Slot.ACCESSORY1,
        net.sourceforge.kolmafia.equipment.Slot.ACCESSORY2,
        net.sourceforge.kolmafia.equipment.Slot.ACCESSORY3,
        net.sourceforge.kolmafia.equipment.Slot.FAMILIAR
      };
      String[] cli = {"hat", "weapon", "off-hand", "back", "shirt", "pants", "acc1", "acc2", "acc3", "familiar"};
      String[] label = {"Hat", "Weapon", "Off-hand", "Back", "Shirt", "Pants", "Accessory 1", "Accessory 2", "Accessory 3", "Familiar item"};
      var lists = net.sourceforge.kolmafia.session.EquipmentManager.getEquipmentLists();
      first = true;
      for (int i = 0; i < slots.length; i++) {
        net.sourceforge.kolmafia.AdventureResult cur = net.sourceforge.kolmafia.session.EquipmentManager.getEquipment(slots[i]);
        String curName = (cur == null || cur.getItemId() <= 0) ? "" : cur.getName();
        if (!first) sb.append(',');
        first = false;
        sb.append('{');
        kv(sb, "slot", cli[i]);
        kv(sb, "label", label[i]);
        kv(sb, "item", curName);
        kv(sb, "img", cur == null ? "" : itemImg(cur.getItemId()));
        sb.append(",\"options\":[");
        boolean f2 = true;
        java.util.List<net.sourceforge.kolmafia.AdventureResult> opts = lists.get(slots[i]);
        if (opts != null) {
          for (net.sourceforge.kolmafia.AdventureResult o : new java.util.ArrayList<>(opts)) {
            if (o == null || o.getItemId() <= 0 || o.getName().isEmpty()) continue;
            if (!f2) sb.append(',');
            f2 = false;
            sb.append('{');
            kv(sb, "name", o.getName());
            kv(sb, "img", itemImg(o.getItemId()));
            kv(sb, "count", o.getCount());
            int power = 0;
            String req = "";
            try {
              power = net.sourceforge.kolmafia.persistence.EquipmentDatabase.getPower(o.getItemId());
              req = net.sourceforge.kolmafia.persistence.EquipmentDatabase.getEquipRequirement(o.getItemId());
            } catch (Throwable ignored) {
            }
            kv(sb, "power", power);
            kv(sb, "req", req == null ? "" : req);
            sb.append('}');
          }
        }
        sb.append("]}");
      }
      sb.append(']');
      appendExtras(sb);
      appendRestores(sb, "restoreHp", true);
      appendRestores(sb, "restoreMp", false);
    } catch (Throwable t) {
      sb.append(",\"error\":").append(quote(String.valueOf(t)));
    }
    return sb.append('}').toString();
  }

  /** Preferences as JSON array: [{name,value,scope}]. Filter by name (case-insensitive). */
  public static synchronized String preferences(String filter) {
    StringBuilder sb = new StringBuilder("[");
    try {
      java.util.TreeMap<String, String> globalDefaults = net.sourceforge.kolmafia.preferences.Preferences.getMap(true, false);
      java.util.TreeMap<String, String> userDefaults = net.sourceforge.kolmafia.preferences.Preferences.getMap(true, true);
      java.util.TreeMap<String, String> globalValues = net.sourceforge.kolmafia.preferences.Preferences.getMap(false, false);
      java.util.TreeMap<String, String> userValues = net.sourceforge.kolmafia.preferences.Preferences.getMap(false, true);

      String lowerFilter = filter == null ? "" : filter.toLowerCase();
      boolean first = true;
      int count = 0;
      final int MAX_RESULTS = 200;

      // Global preferences
      for (java.util.Map.Entry<String, String> entry : globalDefaults.entrySet()) {
        if (count >= MAX_RESULTS) break;
        String name = entry.getKey();
        if (!lowerFilter.isEmpty() && !name.toLowerCase().contains(lowerFilter)) continue;
        String defaultValue = entry.getValue();
        String currentValue = globalValues.getOrDefault(name, defaultValue);
        if (!first) sb.append(',');
        first = false;
        count++;
        sb.append('{');
        kv(sb, "name", name);
        kv(sb, "value", currentValue);
        kv(sb, "default", defaultValue);
        kv(sb, "scope", "global");
        sb.append('}');
      }

      // User preferences
      for (java.util.Map.Entry<String, String> entry : userDefaults.entrySet()) {
        if (count >= MAX_RESULTS) break;
        String name = entry.getKey();
        if (!lowerFilter.isEmpty() && !name.toLowerCase().contains(lowerFilter)) continue;
        String defaultValue = entry.getValue();
        String currentValue = userValues.getOrDefault(name, defaultValue);
        if (!first) sb.append(',');
        first = false;
        count++;
        sb.append('{');
        kv(sb, "name", name);
        kv(sb, "value", currentValue);
        kv(sb, "default", defaultValue);
        kv(sb, "scope", "user");
        sb.append('}');
      }
    } catch (Throwable t) {
      sb.append("{\"error\":").append(quote(String.valueOf(t))).append('}');
    }
    return sb.append(']').toString();
  }
}