#!/usr/bin/env python3
"""Small source patches applied to the synced upstream core (fails loudly if upstream changed)."""
import os, sys
ROOT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'kolmafia-core/src/main/java')
PATCHES = [
    # Android ignores System.setProperty("user.dir"), so let the app choose the data root explicitly.
    ('net/java/dev/spellcast/utilities/UtilityConstants.java',
     '\tpublic static final File ROOT_LOCATION =\n\t\tBoolean.getBoolean( "useCWDasROOT" ) ?',
     '\tpublic static final File ROOT_LOCATION =\n\t\tSystem.getProperty( "kolmob.root" ) != null ?\n\t\tnew File( System.getProperty( "kolmob.root" ) ).getAbsoluteFile() :\n\t\tBoolean.getBoolean( "useCWDasROOT" ) ?'),
    # java.lang.Runtime.version() does not exist on Android; the "old Java" warnings are meaningless there.
    ('net/sourceforge/kolmafia/KoLmafia.java',
     'if (Runtime.version().feature() >= MINIMUM_JAVA_VERSION) return List.of();',
     'if (true) return List.of(); // Android: no Runtime.version()'),
    ('net/sourceforge/kolmafia/session/LoginManager.java',
     '    var version = Runtime.version();\n    if (version.feature() < 21) {',
     '    if (false) { // Android: no Runtime.version()'),
    # The Maximizer keeps its expression in a Swing combo box owned by the GUI; there is no GUI here, so a
    # plain field replaces it (headless desktop KoLmafia has a real combo box, so this never failed there).
    ('net/sourceforge/kolmafia/maximizer/Maximizer.java',
     '  static long combinationLimit;\n',
     '  static long combinationLimit;\n  static String currentExpression = "";\n'),
    ('net/sourceforge/kolmafia/maximizer/Maximizer.java',
     '    MaximizerFrame.expressionSelect.setSelectedItem(maximizerString);',
     '    Maximizer.currentExpression = maximizerString;'),
    ('net/sourceforge/kolmafia/maximizer/Maximizer.java',
     '    String maxMe = (String) MaximizerFrame.expressionSelect.getSelectedItem();',
     '    String maxMe = Maximizer.currentExpression;'),
    # CalendarFrame is a Swing class stubbed out here (its formats are null); `moons` only needs the long date format.
    ('net/sourceforge/kolmafia/textui/command/ShowDataCommand.java',
     'DateTimeFormatter.ofPattern(CalendarFrame.LONG_FORMAT.toPattern())',
     'DateTimeFormatter.ofPattern("MMMM d, yyyy")'),
    # ChatBuffer.flush() can compute a negative skip when entries were trimmed between scheduling and running;
    # on the desktop the Swing event thread swallows the exception, on Android it would kill the app.
    ('net/sourceforge/kolmafia/chat/ChatBuffer.java',
     'this.entries.stream().skip(this.entries.size() - this.pendingCount).toList());',
     'this.entries.stream().skip(Math.max(0, this.entries.size() - this.pendingCount)).toList());'),
    # Scripts that ask a question (user_confirm, user_prompt, ...) wait in KoLmafiaCLI.getNextLine; let the app know
    # so it can show a dialog (the app then feeds the answer through System.in, see engine KolStdin).
    ('net/sourceforge/kolmafia/KoLmafiaCLI.java',
     '  public static final KoLmafiaCLI DEFAULT_SHELL = new KoLmafiaCLI(System.in);',
     '  public static final KoLmafiaCLI DEFAULT_SHELL = new KoLmafiaCLI(System.in);\n\n  /** Kolmob: called when a script is waiting for typed input. */\n  public static volatile java.util.function.Consumer<String> promptHook;'),
    ('net/sourceforge/kolmafia/KoLmafiaCLI.java',
     '      if (KoLmafiaCLI.this.queuedLines.isEmpty() && KoLmafiaCLI.this.retriever.isAlive()) {\n        PauseObject pauser = new PauseObject();',
     '      if (KoLmafiaCLI.promptHook != null\n          && KoLmafiaCLI.DEFAULT_SHELL == KoLmafiaCLI.this\n          && KoLmafiaCLI.this.queuedLines.isEmpty()) {\n        KoLmafiaCLI.promptHook.accept(message == null ? "" : message);\n      }\n\n      if (KoLmafiaCLI.this.queuedLines.isEmpty() && KoLmafiaCLI.this.retriever.isAlive()) {\n        PauseObject pauser = new PauseObject();'),
    # Kolmob: start only the thread that reads standard input (not the command processor that would run what is typed)
    ('net/sourceforge/kolmafia/KoLmafiaCLI.java',
     '  public void listenForCommands() {',
     '  /** Kolmob: lets scripts that ask a question read the answer from System.in (the app feeds it). */\n  public void startInputRetriever() {\n    if (!this.retriever.isAlive()) {\n      this.retriever.setDaemon(true);\n      this.retriever.start();\n    }\n  }\n\n  public void listenForCommands() {'),
    # Memory: KoLmafia caches one fully parsed interpreter per script file. Autoscend's pre/post-adventure, choice and
    # combat scripts each import all of autoscend (~140 MB parsed each), which exhausts the phone's 512 MB heap after a few
    # dozen turns. Hold the cache through soft references so the runtime drops cached parses before running out of memory
    # (a dropped script is simply parsed again the next time it runs).
    ('net/sourceforge/kolmafia/KoLmafiaASH.java',
     '''  private static final HashMap<File, ScriptRuntime> INTERPRETERS = new HashMap<>();''',
     '''  private static final HashMap<File, java.lang.ref.SoftReference<ScriptRuntime>> INTERPRETERS =
      new HashMap<>();'''),
    ('net/sourceforge/kolmafia/KoLmafiaASH.java',
     '''    boolean createInterpreter = !KoLmafiaASH.TIMESTAMPS.containsKey(toExecute);
''',
     '''    boolean createInterpreter = !KoLmafiaASH.TIMESTAMPS.containsKey(toExecute);
    ScriptRuntime cached = null;
'''),
    ('net/sourceforge/kolmafia/KoLmafiaASH.java',
     '''      ScriptRuntime interpreter = KoLmafiaASH.INTERPRETERS.get(toExecute);
      if (interpreter instanceof AshRuntime) {''',
     '''      java.lang.ref.SoftReference<ScriptRuntime> ref = KoLmafiaASH.INTERPRETERS.get(toExecute);
      cached = ref == null ? null : ref.get();
      ScriptRuntime interpreter = cached;
      if (interpreter == null) {
        createInterpreter = true;
      } else if (interpreter instanceof AshRuntime) {'''),
    ('net/sourceforge/kolmafia/KoLmafiaASH.java',
     '''      KoLmafiaASH.INTERPRETERS.put(toExecute, interpreter);
    }

    return KoLmafiaASH.INTERPRETERS.get(toExecute);''',
     '''      KoLmafiaASH.INTERPRETERS.put(toExecute, new java.lang.ref.SoftReference<>(interpreter));
      return interpreter;
    }

    return cached;'''),
    ('net/sourceforge/kolmafia/KoLmafiaASH.java',
     '''    for (ScriptRuntime i : KoLmafiaASH.INTERPRETERS.values()) {
      if (i.getRelayRequest() != null) {''',
     '''    for (java.lang.ref.SoftReference<ScriptRuntime> ref : KoLmafiaASH.INTERPRETERS.values()) {
      ScriptRuntime i = ref.get();
      if (i != null && i.getRelayRequest() != null) {'''),
    # Kolmob: a character's passive-skill bonuses are cached and only rebuilt when the skill list is flagged as changed. If
    # that cache is ever rebuilt from a partial skill list (seen as Ed the Undying after buying "Just One More Extra Spleen":
    # spleen limit 25 -> 5, liver 4 -> 0 while the game still said 25/30), KoLmafia calls the player overspleened and
    # refuses every adventure. Before refusing, rebuild the cache once and check again; a really overspleened character
    # still fails the second check.
    ('net/sourceforge/kolmafia/KoLCharacter.java',
     '''  public static boolean isOverspleened() {
    return KoLCharacter.canChew() && KoLCharacter.getSpleenUse() > KoLCharacter.getSpleenLimit();
  }''',
     '''  public static boolean isOverspleened() {
    if (!KoLCharacter.canChew()) {
      return false;
    }
    if (KoLCharacter.getSpleenUse() <= KoLCharacter.getSpleenLimit()) {
      return false;
    }
    // Kolmob: rebuild the cached passive-skill bonuses and look again before calling the player overspleened.
    Modifiers.availableSkillsChanged();
    KoLCharacter.recalculateAdjustments();
    return KoLCharacter.getSpleenUse() > KoLCharacter.getSpleenLimit();
  }'''),
]
bad = 0
for rel, old, new in PATCHES:
    p = os.path.join(ROOT, rel)
    s = open(p, encoding='utf-8').read()
    if new in s:
        continue
    if old not in s:
        print('PATCH FAILED (upstream changed?):', rel); bad += 1; continue
    open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
    print('patched', rel)
sys.exit(1 if bad else 0)
