package net.sourceforge.kolmafia;

import java.util.ArrayList;
import java.util.List;
import net.java.dev.spellcast.utilities.LockableListModel;
import net.sourceforge.kolmafia.ui.UserInterface;
import net.sourceforge.kolmafia.utilities.HTMLListEntry;

/**
 * Kolmob's user interface for the engine (see upstream devdoc/engine-ui-separation.md).
 *
 * <p>Everything else keeps the engine's defaults: work for the user interface thread (chat buffers, highlight
 * refreshes) runs one task at a time on the engine's own background thread, and prompts use the console, which the
 * app answers through KolStdin and KoLmafiaCLI.promptHook.
 */
public final class KolmobUserInterface implements UserInterface {
  public static final KolmobUserInterface INSTANCE = new KolmobUserInterface();

  private volatile boolean darkTheme = false;

  /** The last contact list KoL sent (/who, /friends), published as one immutable snapshot. */
  public record Contacts(int version, String title, List<String> names, List<Boolean> inChat) {}

  private volatile Contacts contacts = new Contacts(0, "", List.of(), List.of());

  private KolmobUserInterface() {}

  /** Tells scripts (is_dark_mode()) whether the app is showing its dark theme. */
  public void setDarkTheme(final boolean dark) {
    this.darkTheme = dark;
  }

  @Override
  public boolean isDarkTheme() {
    return this.darkTheme;
  }

  /** The newest contact list; its version goes up each time KoL sends one. */
  public Contacts contacts() {
    return this.contacts;
  }

  /**
   * KoLmafia hides the reply to /who and /friends from the chat buffer (useContactsFrame, on by default) and hands
   * the list here instead, where the desktop opens its contact list window. Kolmob keeps it for the chat screen.
   */
  @Override
  public void showContacts(final String title, final LockableListModel<?> list) {
    List<String> names = new ArrayList<>();
    List<Boolean> inChat = new ArrayList<>();
    for (Object o : list.toArray()) {
      if (o instanceof HTMLListEntry entry) {
        names.add(entry.getValue());
        // ContactManager colors players in chat black and the rest gray; the color is only in the HTML
        inChat.add(!entry.toString().contains("\"gray\""));
      }
    }
    synchronized (this) {
      this.contacts = new Contacts(this.contacts.version() + 1, title, List.copyOf(names), List.copyOf(inChat));
    }
  }

  @Override
  public void beep() {
    // No terminal bell on the phone; chat notifications are the app's business.
  }
}
