package net.sourceforge.kolmafia;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.sourceforge.kolmafia.persistence.Consumable;
import net.sourceforge.kolmafia.persistence.ConsumablesDatabase;
import net.sourceforge.kolmafia.objectpool.Concoction;
import net.sourceforge.kolmafia.objectpool.ConcoctionPool;
import net.sourceforge.kolmafia.objectpool.ItemPool;
import net.sourceforge.kolmafia.persistence.ConcoctionDatabase;
import net.sourceforge.kolmafia.persistence.ItemDatabase;
import net.sourceforge.kolmafia.persistence.MallPriceDatabase;
import net.sourceforge.kolmafia.persistence.NPCStoreDatabase;
import net.sourceforge.kolmafia.preferences.Preferences;
import net.sourceforge.kolmafia.session.InventoryManager;

/**
 * Native diet planner (Kolmob). Same idea as libram's/garbo's planner: a bounded knapsack per organ
 * (stomach, liver, spleen) maximising (expected adventures x meat-per-adventure) - purchase cost,
 * plus Ode to Booze and milk of magnesium, with the gains KoLmafia's consumables database gives for them.
 *
 * <p>Candidates are what you own (cost 0) and, optionally, what an NPC store sells. Nothing is
 * bought or eaten here; {@link #plan} only returns a JSON plan with the exact commands to run.
 */
public final class KolDiet {
  private KolDiet() {}

  /** Planner switches. Parsed from "key=value;key=value" (see {@link #parse}). */
  public static final class Options {
    long mpa = 0; // <= 0: use the valueOfAdventure preference
    int reserve = 10; // adventures to keep back for crafting and for eating/drinking when low
    boolean owned = true;
    boolean npc = false;
    boolean mall = false;
    boolean craft = false;
    boolean nightcap = false;
    boolean expanders = true; // false: no distention pill, cuppa Voraci tea or sweet tooth (they add room for the day)
    boolean cleaners = true; // false: no pills, teas or other items that empty an organ, and no credit for spleen a food empties
    boolean food = true;
    boolean booze = true;
    boolean spleen = true;
    boolean sim = false; // pretend it is a fresh day: organs empty
    final Set<String> blacklist = new HashSet<>();

    static Options parse(String spec) {
      Options o = new Options();
      for (String name : Preferences.getString("kolmobDietBlacklist").split("\\|")) {
        if (!name.isBlank()) o.blacklist.add(name.trim().toLowerCase());
      }
      if (spec == null) return o;
      for (String part : spec.split(";")) {
        int eq = part.indexOf('=');
        if (eq < 0) continue;
        String k = part.substring(0, eq).trim();
        String v = part.substring(eq + 1).trim();
        boolean b = v.equals("1") || v.equalsIgnoreCase("true");
        switch (k) {
          case "mpa" -> {
            try {
              o.mpa = Long.parseLong(v);
            } catch (NumberFormatException e) {
              // keep default
            }
          }
          case "reserve" -> {
            try {
              o.reserve = Math.max(0, Integer.parseInt(v));
            } catch (NumberFormatException e) {
              // keep default
            }
          }
          case "owned" -> o.owned = b;
          case "npc" -> o.npc = b;
          case "mall" -> o.mall = b;
          case "craft" -> o.craft = b;
          case "night" -> o.nightcap = b;
          case "cleaners" -> o.cleaners = b;
          case "expanders" -> o.expanders = b;
          case "food" -> o.food = b;
          case "booze" -> o.booze = b;
          case "spleen" -> o.spleen = b;
          case "sim" -> o.sim = b;
          case "black" -> {
            for (String n : v.split("\\|")) if (!n.isBlank()) o.blacklist.add(n.trim().toLowerCase());
          }
          default -> {}
        }
      }
      return o;
    }
  }

  private enum Organ {
    FOOD,
    BOOZE,
    SPLEEN
  }

  private static final class Cand {
    String name;
    int itemId;
    Organ organ;
    int size;
    double adv; // expected adventures per unit, before helpers
    double odeBonus; // extra adventures per unit when Ode to Booze is active
    boolean odeHelps; // Ode to Booze adds adventures to this drink (true even when Ode is already active)
    double milkBonus; // extra adventures when milk of magnesium is active (KoLmafia: only foods that give adventures)
    long cost; // meat per unit (0 = already owned)
    int max; // units available
    boolean owned;
    String source = "owned"; // owned | npc | mall | craft

    Consumable k;
    double extra; // meat value on top of the adventures (spleen the item empties)

    double value(double mpa, boolean ode) {
      return (adv + (ode ? odeBonus : 0)) * mpa - cost + extra;
    }
  }

  private static final class Pick {
    Cand c;
    int count;
  }

  private static final class Result {
    double value;
    double adv;
    long cost;
    final List<Pick> picks = new ArrayList<>();
  }

  private static List<Cand> candidates(Options o) {
    List<Cand> out = new ArrayList<>();
    boolean odeActive = KoLConstants.activeEffects.contains(ConsumablesDatabase.ODE);
    Set<Integer> ownedIds = new HashSet<>();
    // owned
    for (AdventureResult ar : KoLConstants.inventory) {
      if (!ar.isItem() || ar.getCount() <= 0) continue;
      Cand c = make(ar.getName(), odeActive, o);
      if (c == null) continue;
      ownedIds.add(c.itemId);
      if (!o.owned) continue;
      c.owned = true;
      c.max = ar.getCount();
      c.cost = 0;
      out.add(c);
    }
    boolean canMall = o.mall && KoLCharacter.canInteract();
    for (Consumable k : ConsumablesDatabase.allConsumables) {
      // NPC store
      if (o.npc && NPCStoreDatabase.contains(k.itemId)) {
        long price = NPCStoreDatabase.availablePrice(k.itemId);
        Cand c = price > 0 ? make(k.name, odeActive, o) : null;
        if (c != null) {
          c.source = "npc";
          c.cost = price;
          c.max = 40;
          out.add(c);
        }
      }
      // mall (only when out of Ronin/Hardcore; uses KoLmafia's cached mall prices)
      if (canMall && ItemDatabase.isTradeable(k.itemId)) {
        long price = MallPriceDatabase.getPrice(k.itemId);
        Cand c = price > 0 ? make(k.name, odeActive, o) : null;
        if (c != null) {
          c.source = "mall";
          c.cost = price;
          c.max = 20;
          out.add(c);
        }
      }
      // crafting: only what the owned ingredients can make right now
      if (o.craft) {
        Concoction conc = ConcoctionPool.get(k.itemId);
        if (conc != null
            && conc.creatable > 0
            && ConcoctionDatabase.isPermittedMethod(conc)
            && !usesBlacklisted(k.itemId, o)) {
          Cand c = make(k.name, odeActive, o);
          if (c != null) {
            c.source = "craft";
            c.cost = (long) conc.getAdventuresNeeded(1, true) * Math.max(1, o.mpa > 0 ? o.mpa : Preferences.getInteger("valueOfAdventure"));
            int advEach = conc.getAdventuresNeeded(1, true);
            if (advEach > o.reserve) continue; // a single craft would eat more than the reserve
            c.max = advEach > 0 ? Math.min(conc.creatable, o.reserve / advEach) : conc.creatable;
            out.add(c);
          }
        }
      }
    }
    // KoLmafia's daily, per-ascension and path limits
    out.removeIf(c -> (c.max = Math.min(c.max, usesLeft(c))) <= 0);
    return out;
  }

  /** True if any ingredient of this item's recipe is blacklisted (so we do not craft with it). */
  private static boolean usesBlacklisted(int itemId, Options o) {
    if (o.blacklist.isEmpty()) return false;
    AdventureResult[] ingredients = ConcoctionDatabase.getIngredients(itemId);
    if (ingredients == null) return false;
    for (AdventureResult ing : ingredients) {
      if (o.blacklist.contains(ing.getName().toLowerCase())) return true;
    }
    return false;
  }

  private static Cand make(String name, boolean odeActive, Options o) {
    Consumable k = ConsumablesDatabase.getConsumableByName(name);
    if (k == null) return null;
    if (o.blacklist.contains(name.toLowerCase())) return null;
    if (!ConsumablesDatabase.meetsLevelRequirement(name)) return null;
    Cand c = new Cand();
    c.name = name;
    c.itemId = k.itemId;
    c.k = k;
    if (k.getRawFullness() != null && k.getFullness() > 0) {
      c.organ = Organ.FOOD;
      c.size = k.getFullness();
    } else if (k.getRawInebriety() != null && k.getInebriety() > 0) {
      c.organ = Organ.BOOZE;
      c.size = k.getInebriety();
    } else if (k.getRawSpleenHit() != null && k.getSpleenHit() > 0) {
      c.organ = Organ.SPLEEN;
      c.size = k.getSpleenHit();
    } else {
      return null;
    }
    // KoLmafia's own numbers: it knows which drinks Ode boosts and which foods milk boosts. Milk is planned on its own below.
    c.adv = ConsumablesDatabase.getAverageAdventures(k, false, odeActive, false);
    if (c.adv <= 0) return null; // stat-only items are not planned here
    if (c.organ == Organ.BOOZE && !odeActive) c.odeBonus = ConsumablesDatabase.getAverageAdventures(k, false, true, false) - c.adv;
    if (c.organ == Organ.BOOZE)
      c.odeHelps = ConsumablesDatabase.getAverageAdventures(k, false, true, false) > ConsumablesDatabase.getAverageAdventures(k, false, false, false);
    if (c.organ == Organ.FOOD) c.milkBonus = ConsumablesDatabase.getAverageAdventures(k, false, false, true) - c.adv;
    return c;
  }

  /**
   * Casts Ode to Booze until it has at least {@code turns} turns. A failed cast (e.g. no room for another song) is only a
   * warning in KoLmafia, so the command aborts the run when the turns are still short; in Ronin or Hardcore KoLmafia
   * would otherwise ask whether to drink without it.
   */
  static String odeCommand(int turns) {
    return "ash int need = " + turns + "; int t = have_effect($effect[Ode to Booze]); "
        + "while (t < need) { use_skill(1, $skill[The Ode to Booze]); if (have_effect($effect[Ode to Booze]) <= t) break; t = have_effect($effect[Ode to Booze]); } "
        + "if (t < need) abort(\"Ode to Booze has \" + t + \" of the \" + need + \" turns the booze needs; stopping before the booze.\");";
  }

  /** Bounded knapsack for one organ. */
  private static Result solve(List<Cand> all, Organ organ, int cap, double mpa, boolean ode) {
    Result r = new Result();
    if (cap <= 0) return r;
    List<Cand> items = new ArrayList<>();
    for (Cand c : all) {
      if (c.organ == organ && c.value(mpa, ode) > 0 && c.size <= cap) items.add(c);
    }
    // expand to units
    List<Cand> units = new ArrayList<>();
    for (Cand c : items) {
      int n = Math.min(c.max, cap / c.size);
      for (int i = 0; i < n; i++) units.add(c);
    }
    int n = units.size();
    double[][] dp = new double[n + 1][cap + 1];
    for (int i = 1; i <= n; i++) {
      Cand c = units.get(i - 1);
      double v = c.value(mpa, ode);
      for (int w = 0; w <= cap; w++) {
        dp[i][w] = dp[i - 1][w];
        if (w >= c.size && dp[i - 1][w - c.size] + v > dp[i][w]) {
          dp[i][w] = dp[i - 1][w - c.size] + v;
        }
      }
    }
    r.value = dp[n][cap];
    int w = cap;
    java.util.Map<Cand, Integer> chosen = new java.util.LinkedHashMap<>();
    for (int i = n; i >= 1; i--) {
      if (dp[i][w] != dp[i - 1][w]) {
        Cand c = units.get(i - 1);
        chosen.merge(c, 1, Integer::sum);
        w -= c.size;
      }
    }
    for (var e : chosen.entrySet()) {
      Pick p = new Pick();
      p.c = e.getKey();
      p.count = e.getValue();
      r.picks.add(p);
      r.adv += (e.getKey().adv + (ode ? e.getKey().odeBonus : 0)) * p.count;
      r.cost += e.getKey().cost * p.count;
    }
    return r;
  }

  /** One thing to do, in order: a unit of food, drink or spleen with what goes with it, or a setup step. */
  private record Step(String group, String kind, String label, List<String> commands) {}

  /** Makes room: a pill or tea adds capacity, a cleaner empties part of an organ (only useful once it is full). */
  private record Room(String name, int food, int booze, int spleen, boolean cleaner, List<String> commands, long cost) {}

  private static int id(String name) {
    return ItemDatabase.getItemId(name);
  }

  private static int have(String name) {
    int id = id(name);
    return id > 0 ? InventoryManager.getCount(id) : 0;
  }

  /** What one more of an item costs to buy with the allowed sources, or -1 when it cannot be bought. */
  private static long buyPrice(String name, Options o) {
    int id = id(name);
    if (id <= 0) return -1;
    if (o.npc && NPCStoreDatabase.contains(id)) {
      long p = NPCStoreDatabase.availablePrice(id);
      if (p > 0) return p;
    }
    if (o.mall && KoLCharacter.canInteract() && ItemDatabase.isTradeable(id)) {
      long p = MallPriceDatabase.getPrice(id);
      if (p > 0) return p;
    }
    return -1;
  }

  /** KoLmafia's daily, per-ascension and path limits for a consumable, leaving out the room left in the organ. */
  private static int usesLeft(Cand c) {
    int n =
        switch (c.organ) {
          case FOOD -> net.sourceforge.kolmafia.request.EatItemRequest.maximumUses(c.itemId, c.name, 0);
          case BOOZE -> net.sourceforge.kolmafia.request.DrinkItemRequest.maximumUses(c.itemId, c.name, 0, true);
          case SPLEEN -> net.sourceforge.kolmafia.request.SpleenItemRequest.maximumUses(c.itemId, c.name, 0);
        };
    return Math.max(0, n);
  }

  /** Adds the uses of an item that makes room: owned ones first (free), then bought ones if a source allows it. */
  private static void addRoom(List<Room> out, Options o, String name, boolean usedToday, int limit, int food, int booze, int spleen, boolean cleaner) {
    if (usedToday || limit <= 0 || (cleaner ? !o.cleaners : !o.expanders)) return;
    int owned = Math.min(have(name), limit);
    for (int i = 0; i < owned; i++) out.add(new Room(name, food, booze, spleen, cleaner, List.of("use 1 " + name), 0));
    long price = buyPrice(name, o);
    if (price > 0) {
      for (int i = owned; i < limit; i++) out.add(new Room(name, food, booze, spleen, cleaner, List.of("buy 1 " + name, "use 1 " + name), price));
    }
  }

  private static List<Room> rooms(Options o) {
    List<Room> r = new ArrayList<>();
    addRoom(r, o, "distention pill", Preferences.getBoolean("_distentionPillUsed"), 1, 1, 0, 0, false);
    addRoom(r, o, "cuppa Voraci tea", Preferences.getBoolean("_voraciTeaUsed"), 1, 1, 0, 0, false);
    addRoom(r, o, "sweet tooth", Preferences.getBoolean("_sweetToothUsed"), 1, 1, 0, 0, false);
    // these take away 1 drunkenness you already have (sober, they do nothing), so they are cleaners, not extra liver
    addRoom(r, o, "synthetic dog hair pill", Preferences.getBoolean("_syntheticDogHairPillUsed"), 1, 0, 1, 0, true);
    addRoom(r, o, "cuppa Sobrie tea", Preferences.getBoolean("_sobrieTeaUsed"), 1, 0, 1, 0, true);
    addRoom(r, o, "spice melange", Preferences.getBoolean("spiceMelangeUsed"), 1, 3, 3, 0, true);
    addRoom(r, o, "Ultra Mega Sour Ball", Preferences.getBoolean("_ultraMegaSourBallUsed"), 1, 3, 3, 0, true);
    addRoom(r, o, "mojo filter", false, 3 - Preferences.getInteger("currentMojoFilters"), 0, 0, 1, true);
    int pants = id("designer sweatpants");
    if (o.cleaners && pants > 0 && KoLCharacter.hasEquipped(ItemPool.get(pants, 1))) {
      int casts = Math.min(3 - Preferences.getInteger("_sweatOutSomeBoozeUsed"), Preferences.getInteger("sweat") / 25);
      for (int i = 0; i < casts; i++) r.add(new Room("Sweat Out Some Booze", 0, 1, 0, true, List.of("cast 1 Sweat Out Some Booze"), 0));
    }
    return r;
  }

  private static double planValue(List<Cand> all, int[] caps, double mpa, boolean ode) {
    return solve(all, Organ.FOOD, caps[0], mpa, false).value
        + solve(all, Organ.BOOZE, caps[1], mpa, ode).value
        + solve(all, Organ.SPLEEN, caps[2], mpa, false).value;
  }

  /** Average extra adventures from Ol' Scratch's salad fork or Frosty's frosty mug: x1.5 on salads and beers, else x1.3, rounded down. */
  private static double forkMugGain(Cand c) {
    if (c.k == null) return 0;
    int start = c.k.adventureStart, end = c.k.adventureEnd;
    if (end < start || end <= 0) return 0;
    double m = (c.organ == Organ.FOOD ? c.k.isSalad() : c.k.isBeer()) ? 1.5 : 1.3;
    double sum = 0;
    for (int i = start; i <= end; i++) sum += Math.floor(i * m) - i;
    return sum / (end - start + 1);
  }

  /** Special Seasoning: +1 adventure on a food whose range is at most one wide, else +0.5 on average. */
  private static double seasoningGain(Cand c) {
    if (c.k == null || c.k.adventureEnd <= 0) return 0;
    return c.k.adventureEnd - c.k.adventureStart <= 1 ? 1 : 0.5;
  }

  private static void unitPrep(List<String> cmds, Cand c) {
    switch (c.source) {
      case "craft" -> cmds.add("create 1 " + c.name);
      case "npc", "mall" -> cmds.add("buy 1 " + c.name);
      default -> {}
    }
  }

  /** A per-unit booster for food: up to {@code count} uses, the owned ones free, then bought ones. */
  private static final class Supply {
    final String name;
    int owned;
    final long price;

    Supply(String name, Options o) {
      this.name = name;
      this.owned = have(name);
      this.price = buyPrice(name, o);
    }

    /** Takes one if worth it for a gain in meat; returns the commands to get and use it, or null. */
    List<String> take(double gainMeat, String useVerb) {
      if (gainMeat <= 0) return null;
      if (owned > 0) {
        owned--;
        return useVerb == null ? List.of() : List.of(useVerb + " 1 " + name);
      }
      if (price > 0 && gainMeat > price) {
        List<String> l = new ArrayList<>();
        l.add("buy 1 " + name);
        if (useVerb != null) l.add(useVerb + " 1 " + name);
        return l;
      }
      return null;
    }
  }

  private static final class Unit {
    final Cand c;
    final List<String> before = new ArrayList<>();
    double bonus;

    Unit(Cand c) {
      this.c = c;
    }
  }

  private static List<Unit> units(Result r) {
    List<Unit> out = new ArrayList<>();
    for (Pick p : r.picks) for (int i = 0; i < p.count; i++) out.add(new Unit(p.c));
    out.sort(Comparator.comparingDouble((Unit u) -> -u.c.adv));
    return out;
  }

  public static String plan(String spec) {
    Options o = Options.parse(spec);
    double mpa = o.mpa > 0 ? o.mpa : Preferences.getInteger("valueOfAdventure");
    boolean sim = o.sim;
    int foodCap = !o.food ? 0 : KoLCharacter.getStomachCapacity() - (sim ? 0 : KoLCharacter.getFullness());
    int boozeCap = !o.booze ? 0 : KoLCharacter.getLiverCapacity() - (sim ? 0 : KoLCharacter.getInebriety());
    int spleenCap = !o.spleen ? 0 : KoLCharacter.getSpleenLimit() - (sim ? 0 : KoLCharacter.getSpleenUse());
    List<String> notes = new ArrayList<>();

    List<Cand> all = candidates(o);

    boolean haveOde = KoLCharacter.hasSkill("The Ode to Booze");
    boolean odeActive = KoLConstants.activeEffects.contains(ConsumablesDatabase.ODE);
    boolean odeMaybe = haveOde || odeActive;

    // Spleen cleaned by the extra-greasy slider or pickle juice (5 each) is worth what the best spleen item gives for 5 spleen.
    if (o.spleen && o.cleaners) {
      double perSpleen = 0;
      for (Cand c : all) {
        if (c.organ == Organ.SPLEEN && c.max * c.size >= 5) perSpleen = Math.max(perSpleen, c.value(mpa, false) / c.size);
      }
      for (Cand c : all) {
        if (c.itemId == id("extra-greasy slider") || c.itemId == id("jar of fermented pickle juice")) c.extra = 5 * perSpleen;
      }
    }

    // Pills, teas and cleaners: each one is kept only if the extra room is worth more than it costs.
    int[] caps = {foodCap, boozeCap, spleenCap};
    double base = planValue(all, caps, mpa, odeMaybe);
    List<Room> roomsUsed = new ArrayList<>();
    for (Room rm : rooms(o)) {
      int f = o.food ? rm.food() : 0;
      // a cleaner only empties what is already there: the liver side of a stomach-and-liver cleaner counts only if it is that full now
      int b = !o.booze ? 0 : rm.cleaner() && rm.food() > 0 && (sim || KoLCharacter.getInebriety() < rm.booze()) ? 0 : rm.booze();
      int s = o.spleen ? rm.spleen() : 0;
      if (f + b + s == 0) continue;
      int[] next = {caps[0] + f, caps[1] + b, caps[2] + s};
      double v = planValue(all, next, mpa, odeMaybe);
      if (v - base > rm.cost()) {
        caps = next;
        base = v;
        roomsUsed.add(new Room(rm.name(), f, b, s, rm.cleaner(), rm.commands(), rm.cost()));
      }
    }

    Result food = solve(all, Organ.FOOD, caps[0], mpa, false);
    Result boozePlain = solve(all, Organ.BOOZE, caps[1], mpa, false);
    Result boozeOde = solve(all, Organ.BOOZE, caps[1], mpa, true);
    double odeGain = boozeOde.adv - boozePlain.adv;
    boolean useOde = !odeActive && haveOde && odeGain >= 2;
    Result booze = (useOde || odeActive) ? boozeOde : boozePlain;
    if (odeActive) odeGain = 0;
    boolean odeNow = useOde || odeActive;

    // Overdrink. Only ONE drink can carry you past the liver limit, and the liver resets to empty at rollover, so the
    // overshoot costs nothing tomorrow; the only cost is that you cannot adventure again today. So for each owned booze N:
    // plan the liver without one unit of N, then drink N last. Pick the N with the most total adventures that really does cross
    // the limit. It only counts if it beats the plain plan.
    Cand night = null;
    int nightOver = 0;
    double nightNet = 0;
    if (o.nightcap && o.booze) {
      double bestNet = booze.adv + 0.5;
      Result bestAlt = null;
      for (Cand n : all) {
        if (n.organ != Organ.BOOZE || !n.owned || n.max < 1) continue;
        n.max--;
        Result alt = solve(all, Organ.BOOZE, caps[1], mpa, odeNow);
        n.max++;
        int used = 0;
        for (Pick p : alt.picks) used += p.count * p.c.size;
        int over = n.size - (caps[1] - used);
        if (over <= 0) continue; // fits in the liver: the normal plan already covers it
        double total = alt.adv + n.adv + (odeNow ? n.odeBonus : 0);
        if (total > bestNet) {
          bestNet = total;
          night = n;
          nightOver = over;
          bestAlt = alt;
        }
      }
      if (night != null) {
        nightNet = bestNet - booze.adv;
        booze = bestAlt;
      }
    }

    // Spleen emptied by the slider or pickle juice in the plan
    int cleanedByFood = 0, cleanedByBooze = 0;
    if (o.spleen) {
      for (Pick p : food.picks) if (p.c.extra > 0) cleanedByFood += 5 * p.count;
      for (Pick p : booze.picks) if (p.c.extra > 0) cleanedByBooze += 5 * p.count;
    }
    Result spleen = solve(all, Organ.SPLEEN, caps[2] + cleanedByFood + cleanedByBooze, mpa, false);

    double extraAdv = 0;

    // ---- food: one step per unit, with what goes with it
    List<Unit> foodUnits = units(food);
    // milk of magnesium boosts the next food eaten (by KoLmafia's numbers): eat the food it boosts most first
    int milkId = id("milk of magnesium");
    boolean milkActive = Preferences.getBoolean("milkOfMagnesiumActive");
    double milkGain = 0;
    Unit milkUnit = null;
    for (Unit u : foodUnits) if (u.c.milkBonus > milkGain) { milkGain = u.c.milkBonus; milkUnit = u; }
    boolean useMilk = !milkActive && milkId > 0 && InventoryManager.getCount(milkId) > 0 && milkGain > 0;
    if (milkUnit != null && (useMilk || milkActive)) {
      foodUnits.remove(milkUnit);
      foodUnits.add(0, milkUnit);
      if (useMilk) milkUnit.before.add("use 1 milk of magnesium");
      milkUnit.bonus += milkGain;
      extraAdv += milkGain;
    }
    // Ol' Scratch's salad fork: once a day, on the food it helps most
    if (!Preferences.getBoolean("_saladForkUsed") && !foodUnits.isEmpty()) {
      Unit best = null;
      for (Unit u : foodUnits) if (best == null || forkMugGain(u.c) > forkMugGain(best.c)) best = u;
      List<String> get = new Supply("Ol' Scratch's salad fork", o).take(forkMugGain(best.c) * mpa, "eat");
      if (get != null) {
        best.before.addAll(get);
        best.bonus += forkMugGain(best.c);
        extraAdv += forkMugGain(best.c);
        notes.add("Ol' Scratch's salad fork on " + best.c.name + " (+" + round(forkMugGain(best.c)) + " adventures; KoLmafia gets you the hot resistance it needs)");
      }
    }
    // Per-food boosters. Mini kiwi aioli adds one adventure per fullness, so it goes on the biggest foods first.
    Supply aioli = new Supply("mini kiwi aioli", o);
    Supply whet = new Supply("whet stone", o);
    Supply seasoning = new Supply("Special Seasoning", o);
    Supply mayo = new Supply("Mayoflex", o);
    AdventureResult workshed = net.sourceforge.kolmafia.request.CampgroundRequest.getCurrentWorkshedItem();
    String minder = Preferences.getString("mayoMinderSetting");
    boolean mayoOk = workshed != null && workshed.getItemId() == ItemPool.MAYO_CLINIC && (minder.isEmpty() || minder.equals("Mayoflex"));
    int aioliN = 0, whetN = 0, seasonN = 0, mayoN = 0;
    List<Unit> bySize = new ArrayList<>(foodUnits);
    bySize.sort(Comparator.comparingInt((Unit u) -> -u.c.size));
    for (Unit u : bySize) {
      List<String> a = aioli.take(u.c.size * mpa, "use");
      if (a != null) { u.before.addAll(a); u.bonus += u.c.size; extraAdv += u.c.size; aioliN++; }
    }
    for (Unit u : foodUnits) {
      List<String> w = whet.take(mpa, "use");
      if (w != null) { u.before.addAll(w); u.bonus += 1; extraAdv += 1; whetN++; }
      // KoL adds Special Seasoning to each food by itself while you have some, so only buying it is a step
      List<String> sp = seasoning.take(seasoningGain(u.c) * mpa, null);
      if (sp != null) { u.before.addAll(sp); u.bonus += seasoningGain(u.c); extraAdv += seasoningGain(u.c); seasonN++; }
      if (mayoOk) {
        // with the Mayo Minder set to Mayoflex KoLmafia uses it itself; otherwise use it before the food
        List<String> m = mayo.take(mpa, minder.isEmpty() ? "use" : null);
        if (m != null) { u.before.addAll(m); u.bonus += 1; extraAdv += 1; mayoN++; }
      }
    }
    if (aioliN > 0) notes.add("Mini kiwi aioli on " + aioliN + " food" + (aioliN > 1 ? "s" : "") + " (+1 adventure per fullness)");
    if (whetN > 0) notes.add("Whet stone on " + whetN + " food" + (whetN > 1 ? "s" : "") + " (+1 adventure each)");
    if (seasonN > 0) notes.add("Special Seasoning on " + seasonN + " food" + (seasonN > 1 ? "s" : "") + " (KoL adds it as you eat)");
    if (mayoN > 0) notes.add("Mayoflex on " + mayoN + " food" + (mayoN > 1 ? "s" : "") + " (+1 adventure each)");

    // ---- booze
    List<Unit> boozeUnits = units(booze);
    Unit shotUnit = null;
    // mime army shotglass: once a day, one size-1 drink does not count against the liver; KoLmafia uses it by itself
    if (o.booze && !Preferences.getBoolean("_mimeArmyShotglassUsed") && have("mime army shotglass") > 0) {
      Cand shot = bestLeftover(all, booze, night, mpa, odeNow);
      if (shot != null) {
        Unit u = new Unit(shot);
        shotUnit = u;
        boozeUnits.add(0, u);
        double a = shot.adv + (odeNow ? shot.odeBonus : 0);
        extraAdv += a;
        booze.cost += shot.cost;
        notes.add("Mime army shotglass: one more " + shot.name + " for free liver (+" + round(a) + " adventures)");
      }
    }
    // Frosty's frosty mug: once a day, on the drink it helps most
    if (!Preferences.getBoolean("_frostyMugUsed") && !boozeUnits.isEmpty()) {
      Unit best = null;
      for (Unit u : boozeUnits) if (best == null || forkMugGain(u.c) > forkMugGain(best.c)) best = u;
      List<String> get = new Supply("Frosty's frosty mug", o).take(forkMugGain(best.c) * mpa, "drink");
      if (get != null) {
        best.before.addAll(get);
        best.bonus += forkMugGain(best.c);
        extraAdv += forkMugGain(best.c);
        notes.add("Frosty's frosty mug on " + best.c.name + " (+" + round(forkMugGain(best.c)) + " adventures; KoLmafia gets you the cold resistance it needs)");
      }
    }

    // ---- steps, in order: spleen that fits now, food (with cleaners as the stomach fills), spleen freed by the slider,
    // then booze (Ode first, cleaners as the liver fills) and spleen freed by pickle juice, then the overdrink.
    List<Step> steps = new ArrayList<>();
    List<Unit> spleenUnits = units(spleen);
    int spleenRoom = spleenCap;
    List<Room> spleenCleaners = new ArrayList<>();
    for (Room rm : roomsUsed) if (rm.spleen() > 0) spleenCleaners.add(rm);
    java.util.Iterator<Unit> si = spleenUnits.iterator();
    List<Unit> spleenLater = new ArrayList<>();
    while (si.hasNext()) {
      Unit u = si.next();
      while (u.c.size > spleenRoom && !spleenCleaners.isEmpty() && spleenCap - spleenRoom + KoLCharacter.getSpleenUse() > 0) {
        Room rm = spleenCleaners.remove(0);
        steps.add(new Step("spleen", "setup", rm.name(), rm.commands()));
        spleenRoom += rm.spleen();
      }
      if (u.c.size <= spleenRoom) {
        steps.add(unitStep("spleen", "chew", u));
        spleenRoom -= u.c.size;
      } else {
        spleenLater.add(u);
      }
    }

    int foodRoom = foodCap;
    List<Room> foodCleaners = new ArrayList<>();
    for (Room rm : roomsUsed) {
      if (rm.food() > 0 && !rm.cleaner()) {
        steps.add(new Step("food", "setup", rm.name(), rm.commands()));
        foodRoom += rm.food();
      } else if (rm.food() > 0) {
        foodCleaners.add(rm);
      }
    }
    for (Unit u : foodUnits) {
      while (u.c.size > foodRoom && !foodCleaners.isEmpty()) {
        Room rm = foodCleaners.remove(0);
        steps.add(new Step("food", "setup", rm.name(), rm.commands()));
        foodRoom += rm.food();
      }
      steps.add(unitStep("food", "eat", u));
      foodRoom -= u.c.size;
      if (u.c.extra > 0) {
        int[] room = {spleenRoom + 5};
        spleenLater = placeSpleen(steps, spleenLater, "spleen", room);
        spleenRoom = room[0];
      }
    }
    int boozeRoom = boozeCap;
    List<Room> boozeCleaners = new ArrayList<>();
    for (Room rm : roomsUsed) {
      if (rm.booze() > 0 && !rm.cleaner()) {
        steps.add(new Step("booze", "setup", rm.name(), rm.commands()));
        boozeRoom += rm.booze();
      } else if (rm.booze() > 0 && rm.food() == 0) {
        boozeCleaners.add(rm);
      } else if (rm.booze() > 0) {
        boozeRoom += rm.booze(); // a stomach-and-liver cleaner, used with the food above
      }
    }
    // Each drink uses up as many Ode turns as its drunkenness, so cast until the whole planned booze is covered.
    int odeNeed = 0;
    for (Unit u : boozeUnits) if (u != shotUnit && u.c.odeHelps) odeNeed += u.c.size;
    if (odeNow && haveOde && odeNeed > 0) steps.add(new Step("booze", "setup", "Ode to Booze", List.of(odeCommand(odeNeed))));
    for (Unit u : boozeUnits) {
      boolean free = u == shotUnit;
      while (!free && u.c.size > boozeRoom && !boozeCleaners.isEmpty()) {
        Room rm = boozeCleaners.remove(0);
        steps.add(new Step("booze", "setup", rm.name(), rm.commands()));
        boozeRoom += rm.booze();
      }
      steps.add(unitStep("booze", "drink", u));
      if (!free) boozeRoom -= u.c.size;
      if (u.c.extra > 0) {
        int[] room = {spleenRoom + 5};
        spleenLater = placeSpleen(steps, spleenLater, "booze", room);
        spleenRoom = room[0];
      }
    }
    for (Unit sp : spleenLater) steps.add(unitStep("spleen", "chew", sp));

    // Stooper adds one liver while it is your familiar: at the end of the day, switch to it for one more drink before overdrinking.
    if (night != null) {
      List<String> nightCmds = new ArrayList<>();
      var stooper = KoLCharacter.usableFamiliar(net.sourceforge.kolmafia.objectpool.FamiliarPool.STOOPER);
      if (stooper != null && KoLCharacter.getFamiliar() != stooper) {
        Cand extra = bestLeftover(all, booze, night, mpa, odeNow);
        if (extra != null) {
          nightCmds.add("familiar Stooper");
          List<String> c1 = new ArrayList<>();
          unitPrep(c1, extra);
          nightCmds.addAll(c1);
          nightCmds.add("drink 1 " + extra.name);
          double a = extra.adv + (odeNow ? extra.odeBonus : 0);
          nightNet += a;
          notes.add("Stooper: switch to it at the end of the day for one more " + extra.name + " (+" + round(a) + " adventures)");
        }
      }
      for (Room rm : boozeCleaners) nightCmds.addAll(0, rm.commands());
      if (odeNow && haveOde && night.odeHelps) nightCmds.add(odeCommand(night.size));
      nightCmds.add("drink 1 " + night.name);
      steps.add(new Step("night", "drink", night.name, nightCmds));
    }

    for (Room rm : roomsUsed) {
      String what = (rm.food() > 0 ? "+" + rm.food() + " stomach " : "") + (rm.booze() > 0 ? "+" + rm.booze() + " liver " : "") + (rm.spleen() > 0 ? "+" + rm.spleen() + " spleen " : "");
      notes.add(rm.name() + ": " + what.trim() + (rm.cost() > 0 ? " (" + rm.cost() + " meat)" : ""));
    }

    double totalAdv = food.adv + booze.adv + spleen.adv + extraAdv;
    long totalCost = food.cost + booze.cost + spleen.cost;
    for (Room rm : roomsUsed) totalCost += rm.cost();

    StringBuilder sb = new StringBuilder("{");
    put(sb, "ok", true);
    put(sb, "mpa", (long) mpa);
    put(sb, "foodSpace", foodCap);
    put(sb, "boozeSpace", boozeCap);
    put(sb, "spleenSpace", spleenCap);
    put(sb, "totalAdventures", round(totalAdv));
    put(sb, "totalCost", totalCost);
    put(sb, "haveOde", haveOde);
    put(sb, "useOde", useOde);
    put(sb, "odeGain", round(odeGain));
    put(sb, "useMilk", useMilk);
    put(sb, "milkGain", round(milkGain));
    put(sb, "mallBlocked", o.mall && !KoLCharacter.canInteract());
    put(sb, "simulated", sim);
    put(sb, "reserve", o.reserve);
    put(sb, "adventuresNow", KoLCharacter.getAdventuresLeft());
    sb.append(",\"items\":[");
    boolean firstItem = true;
    firstItem = items(sb, food, "food", firstItem, false);
    firstItem = items(sb, booze, "booze", firstItem, odeNow);
    firstItem = items(sb, spleen, "spleen", firstItem, false);
    sb.append("],\"steps\":[");
    for (int i = 0; i < steps.size(); i++) {
      Step st = steps.get(i);
      if (i > 0) sb.append(',');
      sb.append('{');
      put(sb, "group", st.group());
      put(sb, "kind", st.kind());
      put(sb, "label", st.label());
      sb.append(",\"commands\":[");
      for (int j = 0; j < st.commands().size(); j++) {
        if (j > 0) sb.append(',');
        sb.append(quote(st.commands().get(j)));
      }
      sb.append("]}");
    }
    sb.append("],\"notes\":[");
    for (int i = 0; i < notes.size(); i++) {
      if (i > 0) sb.append(',');
      sb.append(quote(notes.get(i)));
    }
    sb.append("],\"tips\":[");
    boolean firstTip = true;
    for (String t : tips()) {
      if (!firstTip) sb.append(',');
      firstTip = false;
      sb.append(quote(t));
    }
    sb.append("]");
    if (night != null) {
      sb.append(",\"nightcap\":{");
      put(sb, "name", night.name);
      put(sb, "size", night.size);
      put(sb, "adventures", round(night.adv + (odeNow ? night.odeBonus : 0)));
      put(sb, "overshoot", nightOver);
      put(sb, "netGain", round(nightNet));
      sb.append('}');
    }
    sb.append('}');
    return sb.toString();
  }

  private static double round(double d) {
    return Math.round(d * 10) / 10.0;
  }

  /** Spleen units that did not fit yet go in once a slider has emptied some spleen. */
  private static List<Unit> placeSpleen(List<Step> steps, List<Unit> later, String group, int[] room) {
    List<Unit> left = new ArrayList<>();
    for (Unit u : later) {
      if (u.c.size <= room[0]) {
        steps.add(unitStep(group, "chew", u));
        room[0] -= u.c.size;
      } else {
        left.add(u);
      }
    }
    return left;
  }

  /** The best size-1 drink still available after the plan (and the overdrink) took theirs. */
  private static Cand bestLeftover(List<Cand> all, Result booze, Cand night, double mpa, boolean ode) {
    Cand best = null;
    for (Cand c : all) {
      if (c.organ != Organ.BOOZE || c.size != 1) continue;
      int taken = c == night ? 1 : 0;
      for (Pick p : booze.picks) if (p.c == c) taken += p.count;
      if (c.max - taken < 1 || c.value(mpa, ode) <= 0) continue;
      if (best == null || c.value(mpa, ode) > best.value(mpa, ode)) best = c;
    }
    return best;
  }

  private static Step unitStep(String group, String verb, Unit u) {
    List<String> cmds = new ArrayList<>();
    unitPrep(cmds, u.c);
    cmds.addAll(u.before);
    cmds.add(verb + " 1 " + u.c.name);
    return new Step(group, verb, u.c.name, cmds);
  }

  /** Things worth having, and KoLmafia settings that put on gear or get effects for better food and drink. */
  private static List<String> tips() {
    List<String> out = new ArrayList<>();
    boolean haveOven = KoLCharacter.hasOven() || KoLCharacter.hasRange();
    boolean haveKit = KoLCharacter.hasCocktailKit() || KoLCharacter.hasBartender();
    if (!haveOven) {
      long price = NPCStoreDatabase.contains(4707) ? NPCStoreDatabase.availablePrice(4707) : 0;
      out.add("Get a E-Z Cook oven (or a Dramatic range), so you can cook food" + (price > 0 ? " (" + price + " meat at an NPC store)" : ""));
    }
    if (!haveKit) {
      long price = NPCStoreDatabase.contains(236) ? NPCStoreDatabase.availablePrice(236) : 0;
      out.add("Get a Queue Du Coq cocktailcrafting kit, so you can mix drinks" + (price > 0 ? " (" + price + " meat at an NPC store)" : ""));
    }
    if (have("mafia pinky ring") > 0 && !Preferences.getBoolean("autoPinkyRing"))
      out.add("You have a mafia pinky ring: turn on the KoLmafia setting autoPinkyRing so it is worn for wine (+12.5%)");
    if (have("tuxedo shirt") > 0 && !Preferences.getBoolean("autoTuxedo"))
      out.add("You have a tuxedo shirt: turn on the KoLmafia setting autoTuxedo so it is worn for martinis (+2)");
    if (have("potion of the field gar") > 0 && !Preferences.getBoolean("autoGarish"))
      out.add("You have a potion of the field gar: turn on the KoLmafia setting autoGarish for +5 on lasagna");
    return out;
  }

  private static boolean items(StringBuilder sb, Result r, String organ, boolean first, boolean ode) {
    for (Pick p : r.picks) {
      if (!first) sb.append(',');
      first = false;
      sb.append('{');
      put(sb, "name", p.c.name);
      put(sb, "organ", organ);
      put(sb, "count", p.count);
      put(sb, "size", p.c.size);
      put(sb, "adventuresEach", Math.round((p.c.adv + (ode ? p.c.odeBonus : 0)) * 10) / 10.0);
      put(sb, "source", p.c.source);
      put(sb, "buy", p.c.source.equals("npc") || p.c.source.equals("mall"));
      put(sb, "unitCost", p.c.cost);
      sb.append('}');
    }
    return first;
  }

  private static void put(StringBuilder sb, String k, Object v) {
    if (sb.length() > 1 && sb.charAt(sb.length() - 1) != '{' && sb.charAt(sb.length() - 1) != '[')
      sb.append(',');
    sb.append('"').append(k).append("\":");
    if (v instanceof Number || v instanceof Boolean) sb.append(v);
    else sb.append(quote(String.valueOf(v)));
  }

  private static String quote(String s) {
    StringBuilder b = new StringBuilder("\"");
    for (char ch : s.toCharArray()) {
      if (ch == '"' || ch == '\\') b.append('\\');
      if (ch < 32) b.append(' ');
      else b.append(ch);
    }
    return b.append('"').toString();
  }
}
