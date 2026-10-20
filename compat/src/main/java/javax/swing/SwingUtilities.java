package javax.swing;

/** Android shim: there is no Event Dispatch Thread; work runs on the calling or a helper thread. */
public class SwingUtilities {
  private SwingUtilities() {}

  public static boolean isEventDispatchThread() {
    return false;
  }

  public static void invokeLater(Runnable doRun) {
    // The real Swing event thread survives a failing task (it prints the exception); an uncaught exception on a
    // plain thread would terminate an Android app, so do the same here.
    Runnable safe =
        () -> {
          try {
            doRun.run();
          } catch (Throwable e) {
            System.err.println("invokeLater task failed: " + e);
            e.printStackTrace();
          }
        };
    Thread t = new Thread(safe, "kolmob-invokeLater");
    t.setDaemon(true);
    t.start();
  }

  public static void invokeAndWait(Runnable doRun) {
    try {
      doRun.run();
    } catch (RuntimeException e) {
      System.err.println("invokeAndWait task failed: " + e);
    }
  }
}
