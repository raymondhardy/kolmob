package com.example.kolmafiaandroid.tools

/** What a person can ask the maximizer for, shared by the Gear page ("best gear for") and the Maximizer. */
object GearGoals {
    val list: List<Pair<String, String>> = listOf(
        "Item drop" to "item drop",
        "Meat drop" to "meat drop",
        "Main stat" to "mainstat",
        "Stat gains (main stat exp)" to "mainstat exp",
        "Muscle" to "muscle",
        "Mysticality" to "mysticality",
        "Moxie" to "moxie",
        "More combats (+combat rate)" to "combat rate",
        "Fewer combats (-combat rate)" to "-combat",
        "Initiative" to "init",
        "Max HP" to "hp",
        "Max MP" to "mp",
        "Monster level (+)" to "ml",
        "Monster level (-)" to "-ml",
        "Weapon damage" to "weapon damage",
        "Spell damage" to "spell damage",
        "Damage absorption" to "damage absorption",
        "Familiar weight" to "familiar weight",
        "HP regen" to "hp regen",
        "MP regen" to "mp regen",
    )

    // plain names for KoLmafia's own presets (maximizerList); anything else shows as the expression itself
    private val names: Map<String, String> = list.associate { it.second to it.first } + mapOf(
        "mainstat" to "Main stat", "mus" to "Muscle", "mys" to "Mysticality", "mox" to "Moxie",
        "familiar weight" to "Familiar weight", "hp" to "Max HP", "mp" to "Max MP", "ml" to "Monster level (+)",
        "da" to "Damage absorption", "dr" to "Damage reduction", "+combat -tie" to "More combats (+combat rate)",
        "-combat -tie" to "Fewer combats (-combat rate)", "initiative" to "Initiative", "exp" to "Stat gains (exp)",
        "meat drop" to "Meat drop", "item drop" to "Item drop", "2.0 meat, 1.0 item" to "Meat and items",
        "item, sea" to "Item drop, underwater", "weapon dmg" to "Weapon damage", "ranged dmg" to "Ranged damage",
        "elemental dmg" to "Elemental damage", "spell dmg" to "Spell damage", "adv" to "Adventures",
        "pvp fights" to "PvP fights", "hot res" to "Hot resistance", "cold res" to "Cold resistance",
        "spooky res" to "Spooky resistance", "stench res" to "Stench resistance", "sleaze res" to "Sleaze resistance",
        "all res" to "All resistances", "mp regen" to "MP regen", "+four songs" to "Four songs",
    )

    /** What to call an expression in a list. */
    fun label(expr: String): String = names[expr.trim().lowercase()] ?: expr
}
