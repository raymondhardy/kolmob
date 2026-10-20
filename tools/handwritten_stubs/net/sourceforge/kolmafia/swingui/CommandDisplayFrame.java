// Hand-written for Android (see tools/gen_swingui_stubs.py): there is no Swing window, but the relay command box,
// chat "/command" lines and ASH `cli_execute` queue checks all go through executeCommand(), so it must really run.
package net.sourceforge.kolmafia.swingui;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JTabbedPane;
import net.sourceforge.kolmafia.KoLmafia;
import net.sourceforge.kolmafia.KoLmafiaCLI;
import net.sourceforge.kolmafia.RequestLogger;
import net.sourceforge.kolmafia.utilities.StringUtilities;

public class CommandDisplayFrame extends GenericFrame {
  private static final LinkedBlockingQueue<String> QUEUE = new LinkedBlockingQueue<>();
  private static final AtomicInteger PENDING = new AtomicInteger();
  private static Thread worker;

  public CommandDisplayFrame() { }
  public JTabbedPane getTabbedPane() { return null; }
  public boolean shouldAddStatusBar() { return false; }
  public boolean useSidePane() { return false; }

  /** True while any queued command is waiting or running. */
  public static boolean hasQueuedCommands() {
    return PENDING.get() > 0;
  }

  /** Queues a CLI command; they run one at a time on a background thread, in order. */
  public static synchronized void executeCommand(final String command) {
    if (command == null || command.isBlank()) {
      return;
    }
    PENDING.incrementAndGet();
    QUEUE.add(command);
    if (worker == null || !worker.isAlive()) {
      worker = new Thread(new CommandQueueHandler(), "CommandQueue");
      worker.setDaemon(true);
      worker.start();
    }
  }

  static final class CommandQueueHandler implements Runnable {
    public CommandQueueHandler() { }

    @Override
    public void run() {
      handleQueue();
    }

    public void handleQueue() {
      while (true) {
        String command;
        try {
          command = QUEUE.take();
        } catch (InterruptedException e) {
          return;
        }
        try {
          KoLmafia.forceContinue();
          RequestLogger.printLine();
          RequestLogger.printLine("&gt; " + StringUtilities.globalStringReplace(command, "<", "&lt;"));
          RequestLogger.printLine();
          KoLmafiaCLI.DEFAULT_SHELL.executeLine(command);
        } catch (Throwable t) {
          // a failing command must not kill the queue
        } finally {
          PENDING.decrementAndGet();
        }
      }
    }
  }
}
