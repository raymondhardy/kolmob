package net.sourceforge.kolmafia;

/**
 * Kolmob: lets the app pause the running script. The script thread waits just before its next request to the game
 * (between fight rounds or page loads), so nothing is left half done. Other threads (the Game tab, chat, lookups)
 * are never held. Stopping the script, or it ending, releases the pause.
 */
public final class KolPause {
  private static final String SCRIPT_THREAD = "kol-engine";
  private static volatile boolean paused = false;
  private static volatile boolean waiting = false;

  private KolPause() {}

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

  /** Called at the start of every request. Only the script thread ever waits here. */
  public static void awaitResume() {
    if (!paused
        || !SCRIPT_THREAD.equals(Thread.currentThread().getName())
        || net.sourceforge.kolmafia.request.LoginRequest.kolmobTimingIn()) {
      return;
    }
    waiting = true;
    try {
      while (paused && !KoLmafia.refusesContinue()) {
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
