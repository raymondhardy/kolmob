package com.example.kolmafiaandroid.ui

import net.sourceforge.kolmafia.persistence.ItemDatabase
import net.sourceforge.kolmafia.persistence.MallPriceDatabase

/**
 * Colors for item names. Items of the Month all share one color (used and unused forms alike, e.g. corked and uncorked
 * genie bottle); everything else is tinted by what it sells for in the mall, falling back to autosell value.
 * KoL has no real rarity, so price is the stand-in: common items keep the normal text color.
 */
object ItemTier {
    enum class Tier(val label: String, val dark: Int, val light: Int) {
        IOTM("Item of the Month", 0xFFFF6FD8.toInt(), 0xFFC2185B.toInt()),
        LEGENDARY("1M+ meat", 0xFFFF9800.toInt(), 0xFFE65100.toInt()),
        EPIC("100k+", 0xFFC77DFF.toInt(), 0xFF7B1FA2.toInt()),
        RARE("10k+", 0xFF64B5F6.toInt(), 0xFF1565C0.toInt()),
        UNCOMMON("1k+", 0xFF81C784.toInt(), 0xFF2E7D32.toInt()),
        QUEST("Quest item", 0xFF4DD0E1.toInt(), 0xFF00838F.toInt());
        fun color() = if (KC.dark) dark else light
    }

    // Listed names; a trailing * matches any item starting with the text. Edit to add new items of the month.
    private val iotmNames = listOf(
        "corked genie bottle",
        "genie bottle",
        "cursed monkey's paw",
        "cursed monkey glove",
        "Witchess Set",
        "Clan Floundry",
        "Source terminal",
        "Detective school application",
        "DIY protonic accelerator kit",
        "Dear Past Self Package",
        "Li'l orphan tot",
        "Granny Tood's Thanksgarden Catalog",
        "Build-a-City Gingerbread kit",
        "X-32-F snowman crate",
        "LT&T telegraph office deed",
        "disconnected intergnat",
        "Pantsgiving",
        "Fourth of May Cosplay Saber",
        "Kremlin's Greatest Briefcase",
        "cargo cultist shorts",
        "Mayam Calendar",
        "Boxing Daycare",
        "Eight Days a Week Pill Keeper",
        "unbreakable umbrella",
        "Latte lovers member's mug",
        "Jurassic Parka",
        "Cincho de Mayo",
        "Red-Nosed Snapper",
        "Bird-a-Day calendar",
        "Shorter-Order Cook",
        "Pocket Professor",
        "Cold medicine cabinet",
        "Tome of Clip Art",
        "Libram of Candy Heart Summoning",
        "Tome of Sugar Shummoning",
        "Tome of Snowcone Summoning",
        "Tome of Rad Libs",
        "Grimoire of Geek Summoning",
        "Libram of Divine Favors",
        "Libram of Love Songs",
        "Deck of Every Card",
        "Rain-Doh black box",
        "box of Familiar Jacks",
        "Time-Spinner",
        "Bastille Battalion control rig",
        "hewn moon-rune spoon",
        "Beach Comb",
        "Cursed Magnifying Glass",
        "Everfull Dart Holster",
        "Apriling band helmet",
        "Powerful Glove",
        "cosmic bowling ball",
        "Distant Woods Getaway Brochure",
        "Combat Lover's Locket",
        "Roman Candelabra",
        "Neverending Party invitation envelope",
        "Sept-Ember Censer",
        "industrial fire extinguisher",
        "Guzzlr tablet",
        "Autumn-Aton",
        "model train set",
        "Daylight Shavings Helmet",
        "emotion chip",
        "retrospecs",
        "backup camera",
        "miniature crystal ball",
        "August Scepter",
        "Lil' Doctor bag",
        "Wardrobe-o-Matic",
        "Little Geneticist DNA-Splicing Lab",
        "Kramco Sausage-o-Matic",
        "Spacegate access badge",
        "Eternal Car Battery",
        "Packaged Twelve-Foot Pole",
        "Fish-Shaped Bottle",
        "miniature Gardening",
        "Peridot of Peril",
        "Clan Underground Fireworks Shop",
        "Pocket Meteor Guide",
        "Bag o' Tricks",
        "Haunted Doghouse",
        "Chateau Mantegna room key",
        "Cold Medicine Cabinet",
        "Mr. Screege's spectacles",
        "Sea Horse",
        "Hobo Monkey",
        "Source terminal*",
        "Spacegate*",
        "portable Spacegate*",
        "genie's*",
        "Tome of Clip Art",
        "Libram of*",
        "Clan VIP Lounge invitation"
    )
    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    private val exact = iotmNames.filter { !it.endsWith("*") }.map { norm(it) }.toHashSet()
    private val prefixes = iotmNames.filter { it.endsWith("*") }.map { norm(it) }

    private val states = Regex("^(corked|uncorked|empty|used|open|opened|unopened|packaged|replica)\\s+|\\s*\\((open|used|empty|closed|unopened)\\)$", RegexOption.IGNORE_CASE)

    private fun isIotm(name: String): Boolean {
        val n = norm(name)
        if (n in exact || prefixes.any { n.startsWith(it) }) return true
        val stripped = norm(name.replace(states, "").replace(states, ""))
        return stripped in exact
    }

    private val cache = HashMap<String, Tier?>()

    /** Tier for an item name, or null for a plain item. Cached; safe to call from any thread. */
    @Synchronized fun of(name: String): Tier? = cache.getOrPut(name) {
        try {
            if (isIotm(name)) return@getOrPut Tier.IOTM
            val id = ItemDatabase.getItemId(name)
            if (id <= 0) return@getOrPut null
            if (ItemDatabase.isQuestItem(id)) return@getOrPut Tier.QUEST
            if (!ItemDatabase.isTradeable(id)) return@getOrPut null
            val price = maxOf(MallPriceDatabase.getPrice(id), ItemDatabase.getPriceById(id).toLong())
            when {
                price >= 1_000_000 -> Tier.LEGENDARY
                price >= 100_000 -> Tier.EPIC
                price >= 10_000 -> Tier.RARE
                price >= 1_000 -> Tier.UNCOMMON
                else -> null
            }
        } catch (t: Throwable) { null }
    }
}
