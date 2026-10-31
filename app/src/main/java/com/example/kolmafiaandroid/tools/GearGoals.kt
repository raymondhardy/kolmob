package com.example.kolmafiaandroid.tools

/** What a person can ask the maximizer for, shared by the Gear page ("best gear for") and the Boost tool. */
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
}
