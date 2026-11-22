package net.sourceforge.kolmafia;

/**
 * Kolmob: lets the app pause the running script. The script thread waits just before its next request to the game,
 * but never inside a fight or a choice, so it stops on a plain page. Other threads (the Game tab, chat, lookups and
 * commands typed while paused) are never held; the script stays held until those typed commands finish. Stopping
 * the script, or it ending, releases the pause.
 */
public final class KolPause {
  private static final String SCRIPT_THREAD = "kol-engine";
  private static volatile boolean paused = false;
  private static volatile boolean waiting = false;
  private static final java.util.concurrent.atomic.AtomicInteger asideCommands =
      new java.util.concurrent.atomic.AtomicInteger();

  private static volatile long lastRequestAt = System.currentTimeMillis();

  private KolPause() {}

  /** Marks "something just happened" (a request started, or a command was handed to the engine). */
  public static void touch() {
    lastRequestAt = System.currentTimeMillis();
  }

  /** Milliseconds since the last request started, or since touch(). */
  public static long idleMillis() {
    return System.currentTimeMillis() - lastRequestAt;
  }

  public static void setPaused(boolean value) {
    paused = value;
  }

  public static boolean isPaused() {
    return paused;
  }

  /** True while the script thread is actually held at a request. */
  public static boolean isWaiting() {
    return waiting;
  }

  /** A command typed while the script is paused started (true) or finished (false). */
  public static void aside(boolean started) {
    if (started) asideCommands.incrementAndGet();
    else asideCommands.decrementAndGet();
  }

  private static boolean inFightOrChoice() {
    return net.sourceforge.kolmafia.request.FightRequest.currentRound > 0
        || net.sourceforge.kolmafia.request.FightRequest.inMultiFight
        || net.sourceforge.kolmafia.request.FightRequest.choiceFollowsFight
        || net.sourceforge.kolmafia.request.FightRequest.fightFollowsChoice
        || net.sourceforge.kolmafia.session.ChoiceManager.handlingChoice;
  }

  /** Called at the start of every request. Only the script thread ever waits here. */
  public static void awaitResume() {
    lastRequestAt = System.currentTimeMillis();
    if ((!paused && asideCommands.get() == 0)
        || inFightOrChoice()
        || !SCRIPT_THREAD.equals(Thread.currentThread().getName())
        || net.sourceforge.kolmafia.request.LoginRequest.kolmobTimingIn()) {
      return;
    }
    waiting = true;
    try {
      while ((paused || asideCommands.get() > 0) && !KoLmafia.refusesContinue()) {
        try {
          Thread.sleep(150);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    } finally {
      waiting = false;
    }
  }
}
