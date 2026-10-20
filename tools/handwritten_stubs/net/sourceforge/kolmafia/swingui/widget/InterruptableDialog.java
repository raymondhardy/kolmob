// HAND-WRITTEN Android replacement (tools/handwritten_stubs); copied over the generated stub by gen_swingui_stubs.py.
// user_confirm / user_prompt in ASH come through here. With no keyboard, the question is printed and the answer is
// read from standard input, which the app feeds from a dialog (see engine KolStdin / KolEngine.answerPrompt).
package net.sourceforge.kolmafia.swingui.widget;

import net.sourceforge.kolmafia.KoLmafiaCLI;
import net.sourceforge.kolmafia.RequestLogger;
import net.sourceforge.kolmafia.textui.DataTypes;
import net.sourceforge.kolmafia.textui.parsetree.Value;

public class InterruptableDialog {
  static class InterruptableConfirmDialogBox {
    public InterruptableConfirmDialogBox(String mes) {}

    public Boolean getResult() {
      return null;
    }

    public void setResult(boolean b) {}

    public void run() {}

    public InterruptableConfirmDialogBox() {}
  }

  static class InterruptableInputDialogBox {
    public InterruptableInputDialogBox(String mes) {}

    public String getResult() {
      return "";
    }

    public void setResult(String b) {}

    public boolean getClosed() {
      return false;
    }

    public void setClosed(boolean b) {}

    public void run() {}

    public InterruptableInputDialogBox() {}
  }

  public static Value confirm(Value message, Value timeOut, Value defaultBoolean) {
    RequestLogger.printLine(message.toString());
    RequestLogger.printLine("(Y/N, leave blank to choose N)");
    String reply = KoLmafiaCLI.DEFAULT_SHELL.getNextLine(" > ");
    return DataTypes.makeBooleanValue(reply != null && reply.trim().equalsIgnoreCase("y"));
  }

  public static Value input(Value message, Value timeOut, Value defaultString) {
    RequestLogger.printLine(message.toString());
    RequestLogger.printLine("(type your response)");
    String reply = KoLmafiaCLI.DEFAULT_SHELL.getNextLine(" > ");
    return DataTypes.makeStringValue(reply == null ? "" : reply);
  }

  public InterruptableDialog() {}
}
