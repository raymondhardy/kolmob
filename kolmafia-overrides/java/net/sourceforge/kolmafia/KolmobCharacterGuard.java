package net.sourceforge.kolmafia;

import net.sourceforge.kolmafia.objectpool.SkillPool;
import net.sourceforge.kolmafia.request.CharSheetRequest;
import net.sourceforge.kolmafia.request.GenericRequest;
import net.sourceforge.kolmafia.session.LimitMode;

/**
 * Kolmob's additions around KoLCharacter's cached bonuses. Lives in its own file so the patches to
 * KoLCharacter stay one-line calls into here.
 */
public final class KolmobCharacterGuard {
  private KolmobCharacterGuard() {}

  private static volatile int inputs = Integer.MIN_VALUE;
  private static volatile boolean afterUnderworld = false;

  private static int inputsNow() {
    return java.util.Objects.hash(
        KoLCharacter.getLimitMode(),
        KoLCharacter.getPath(),
        KoLCharacter.getAscensionClass(),
        KoLCharacter.getEffectiveFamiliar(),
        KoLConstants.availableSkillsSet.size(),
        KoLConstants.activeEffects.size());
  }

  /** Called as a recalculation starts: remembers what it is based on, and logs it when Ed is in the Underworld. */
  public static int recalculationStarting() {
    final int seen = inputsNow();
    if (KoLCharacter.getLimitMode() == LimitMode.ED) {
      // record what recalculates the character while Ed is in the Underworld (and from where)
      final StackTraceElement[] stack = new Throwable().getStackTrace();
      final StringBuilder from = new StringBuilder();
      for (int i = 2; i < Math.min(stack.length, 7); ++i) {
        from.append(' ')
            .append(stack[i].getClassName().replaceAll(".*[.]", ""))
            .append('.')
            .append(stack[i].getMethodName());
      }
      RequestLogger.printLine(
          "[kolmob] recalculation inside the Underworld, thread "
              + Thread.currentThread().getName()
              + ", from"
              + from);
    }
    return seen;
  }

  /** Called as a recalculation ends. */
  public static void recalculationDone(final int seen) {
    inputs = seen;
  }

  /** Only the script thread recomputes (never while already inside a recalculation); other threads read as is. */
  public static void ensureFresh() {
    if (inputs == inputsNow()
        || Thread.holdsLock(KoLCharacter.class)
        || !"kol-engine".equals(Thread.currentThread().getName())) {
      return;
    }
    RequestLogger.updateDebugLog(
        "Kolmob: character bonuses were out of date (limit mode, skills, effects, class or familiar changed"
            + " without a recalculation); recalculated.");
    KoLCharacter.recalculateAdjustments();
  }

  /** Called when the limit mode changes. */
  public static void modeChanged(final LimitMode previous, final LimitMode now) {
    if (previous == LimitMode.ED && now == LimitMode.NONE) {
      afterUnderworld = true;
    }
  }

  private static String limitsSummary() {
    return "mode="
        + KoLCharacter.getLimitMode()
        + " ed="
        + KoLCharacter.isEd()
        + " class="
        + KoLCharacter.getAscensionClass()
        + " path="
        + KoLCharacter.getPath()
        + " skills="
        + KoLConstants.availableSkillsSet.size()
        + " stomachSkill="
        + KoLCharacter.hasSkill(SkillPool.REPLACEMENT_STOMACH)
        + " tummy="
        + KoLCharacter.getStomachCapacity()
        + " liver="
        + KoLCharacter.getLiverCapacity()
        + " spleen="
        + KoLCharacter.getSpleenLimit();
  }

  /** Called before an adventure: once after each trip to the Underworld, refresh what Ed's skills and limits are. */
  public static void checkAfterUnderworld() {
    if (!afterUnderworld || GenericRequest.abortIfInFightOrChoice(true)) {
      return;
    }
    afterUnderworld = false;
    RequestLogger.printLine("[kolmob] back from the Underworld: " + limitsSummary());
    RequestThread.postRequest(new CharSheetRequest());
    KoLCharacter.recalculateAdjustments();
    RequestLogger.printLine("[kolmob] after refreshing the character sheet: " + limitsSummary());
  }
}
