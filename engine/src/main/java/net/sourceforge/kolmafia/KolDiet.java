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
import net.sourceforge.kolmafia.persistence.ConcoctionDatabase;
import net.sourceforge.kolmafia.persistence.ItemDatabase;
import net.sourceforge.kolmafia.persistence.MallPriceDatabase;
import net.sourceforge.kolmafia.persistence.NPCStoreDatabase;
import net.sourceforge.kolmafia.preferences.Preferences;
import net.sourceforge.kolmafia.session.InventoryManager;

/**
 * Native diet planner (Kolmob). Same idea as libram's/garbo's planner: a bounded knapsack per organ
 * (stomach, liver, spleen) maximising (expected adventures x meat-per-adventure) - purchase cost,
 * plus the helpers KoLmafia's own consumables database already models (Ode to Booze adds +size
 * adventures per drink; milk of magnesium adds +5 to a food).
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
    long cost; // meat per unit (0 = already owned)
    int max; // units available
    boolean owned;
    String source = "owned"; // owned | npc | mall | craft

    double value(double mpa, boolean ode) {
      return (adv + (ode ? odeBonus : 0)) * mpa - cost;
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
    c.adv = ConsumablesDatabase.getAverageAdventures(k);
    if (c.adv <= 0) return null; // stat-only items are not planned here
    if (c.organ == Organ.BOOZE && !odeActive) c.odeBonus = c.size;
    return c;
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

  /** Back-compat entry point. */
  public static String plan(long mpaIn, boolean allowNpc, boolean nightcap) {
    return plan("mpa=" + mpaIn + ";npc=" + (allowNpc ? 1 : 0) + ";night=" + (nightcap ? 1 : 0));
  }

  public static String plan(String spec) {
    Options o = Options.parse(spec);
    double mpa = o.mpa > 0 ? o.mpa : Preferences.getInteger("valueOfAdventure");
    boolean sim = o.sim;
    int foodCap = !o.food ? 0 : KoLCharacter.getStomachCapacity() - (sim ? 0 : KoLCharacter.getFullness());
    int boozeCap = !o.booze ? 0 : KoLCharacter.getLiverCapacity() - (sim ? 0 : KoLCharacter.getInebriety());
    int spleenCap = !o.spleen ? 0 : KoLCharacter.getSpleenLimit() - (sim ? 0 : KoLCharacter.getSpleenUse());

    List<Cand> all = candidates(o);

    Result food = solve(all, Organ.FOOD, foodCap, mpa, false);
    Result boozePlain = solve(all, Organ.BOOZE, boozeCap, mpa, false);
    Result boozeOde = solve(all, Organ.BOOZE, boozeCap, mpa, true);
    Result spleen = solve(all, Organ.SPLEEN, spleenCap, mpa, false);

    boolean haveOde = KoLCharacter.hasSkill("The Ode to Booze");
    boolean odeActive = KoLConstants.activeEffects.contains(ConsumablesDatabase.ODE);
    double odeGain = boozeOde.adv - boozePlain.adv;
    boolean useOde = !odeActive && haveOde && odeGain >= 2;
    Result booze = (useOde || odeActive) ? boozeOde : boozePlain;
    if (odeActive) odeGain = 0;

    // Overdrink. Only ONE drink can carry you past the liver limit, and the liver resets to empty at rollover, so the
    // overshoot costs nothing tomorrow; the only cost is that you cannot adventure again today. So for each owned booze N:
    // plan the liver without one unit of N, then drink N last. Pick the N with the most total adventures that really does cross
    // the limit. It only counts if it beats the plain plan.
    Cand night = null;
    int nightOver = 0;
    double nightNet = 0;
    if (o.nightcap && o.booze) {
      boolean odeNow = useOde || odeActive;
      double bestNet = booze.adv + 0.5;
      Result bestAlt = null;
      for (Cand n : all) {
        if (n.organ != Organ.BOOZE || !n.owned || n.max < 1) continue;
        n.max--;
        Result alt = solve(all, Organ.BOOZE, boozeCap, mpa, odeNow);
        n.max++;
        int used = 0;
        for (Pick p : alt.picks) used += p.count * p.c.size;
        int over = n.size - (boozeCap - used);
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

    // milk of magnesium: +5 adventures on a food, if we have or can buy one and it is not active
    int milkId = ItemDatabase.getItemId("milk of magnesium");
    boolean milkActive = Preferences.getBoolean("milkOfMagnesiumActive");
    boolean haveMilk = milkId > 0 && InventoryManager.getCount(milkId) > 0;
    boolean useMilk = !milkActive && haveMilk && !food.picks.isEmpty();

    List<String> commands = new ArrayList<>(); // milk, food, spleen: use these early in the day
    List<String> boozeCommands = new ArrayList<>(); // drink late, so a better drop can still replace them
    if (useMilk) commands.add("use 1 milk of magnesium");
    food.picks.sort(Comparator.comparingDouble((Pick p) -> -p.c.adv));
    for (Pick p : food.picks) {
      prep(commands, p);
      commands.add("eat " + p.count + " " + p.c.name);
    }
    if (useOde) boozeCommands.add("cast 1 The Ode to Booze");
    for (Pick p : booze.picks) {
      prep(boozeCommands, p);
      boozeCommands.add("drink " + p.count + " " + p.c.name);
    }
    for (Pick p : spleen.picks) {
      prep(commands, p);
      commands.add("chew " + p.count + " " + p.c.name);
    }

    double totalAdv = food.adv + booze.adv + spleen.adv + (useMilk ? 5 : 0);
    long totalCost = food.cost + booze.cost + spleen.cost;

    StringBuilder sb = new StringBuilder("{");
    put(sb, "ok", true);
    put(sb, "mpa", (long) mpa);
    put(sb, "foodSpace", foodCap);
    put(sb, "boozeSpace", boozeCap);
    put(sb, "spleenSpace", spleenCap);
    put(sb, "totalAdventures", Math.round(totalAdv * 10) / 10.0);
    put(sb, "totalCost", totalCost);
    put(sb, "haveOde", haveOde);
    put(sb, "useOde", useOde);
    put(sb, "odeGain", Math.round(odeGain * 10) / 10.0);
    put(sb, "useMilk", useMilk);
    put(sb, "mallBlocked", o.mall && !KoLCharacter.canInteract());
    put(sb, "simulated", sim);
    put(sb, "reserve", o.reserve);
    put(sb, "adventuresNow", KoLCharacter.getAdventuresLeft());
    sb.append(",\"items\":[");
    boolean first = true;
    first = items(sb, food, "food", first, false);
    first = items(sb, booze, "booze", first, useOde || odeActive);
    first = items(sb, spleen, "spleen", first, false);
    sb.append("],\"commands\":[");
    for (int i = 0; i < commands.size(); i++) {
      if (i > 0) sb.append(',');
      sb.append(quote(commands.get(i)));
    }
    sb.append("],\"boozeCommands\":[");
    for (int i = 0; i < boozeCommands.size(); i++) {
      if (i > 0) sb.append(',');
      sb.append(quote(boozeCommands.get(i)));
    }
    sb.append("]");
    sb.append(",\"tips\":[");
    boolean firstTip = true;
    String[][] tools = {
      {"oven", "E-Z Cook oven (or a Dramatic range), so you can cook food"},
      {"kit", "Queue Du Coq cocktailcrafting kit, so you can mix drinks"}
    };
    boolean haveOven = KoLCharacter.hasOven() || KoLCharacter.hasRange();
    boolean haveKit = KoLCharacter.hasCocktailKit() || KoLCharacter.hasBartender();
    for (String[] t : tools) {
      boolean have = t[0].equals("oven") ? haveOven : haveKit;
      if (have) continue;
      int id = t[0].equals("oven") ? 4707 : 236;
      long price = NPCStoreDatabase.contains(id) ? NPCStoreDatabase.availablePrice(id) : 0;
      if (!firstTip) sb.append(',');
      firstTip = false;
      sb.append(quote("Get a " + t[1] + (price > 0 ? " (" + price + " meat at an NPC store)" : "")));
    }
    sb.append("]");
    if (night != null) {
      sb.append(",\"nightcap\":{");
      put(sb, "name", night.name);
      put(sb, "size", night.size);
      put(sb, "adventures", Math.round(night.adv * 10) / 10.0);
      put(sb, "overshoot", nightOver);
      put(sb, "netGain", Math.round(nightNet * 10) / 10.0);
      put(sb, "command", "drink 1 " + night.name);
      sb.append('}');
    }
    sb.append('}');
    return sb.toString();
  }

  private static void prep(List<String> commands, Pick p) {
    switch (p.c.source) {
      case "craft" -> commands.add("create " + p.count + " " + p.c.name);
      case "npc", "mall" -> commands.add("buy " + p.count + " " + p.c.name);
      default -> {}
    }
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
