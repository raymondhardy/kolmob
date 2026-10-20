package com.example.kolmafiaandroid.tools

import androidx.fragment.app.FragmentManager
import com.example.kolmafiaandroid.fragments.ScriptsFragment
import com.example.kolmafiaandroid.tools.DatabaseFragment
import com.example.kolmafiaandroid.tools.ItemsFragment
import com.example.kolmafiaandroid.tools.LogsFragment
import com.example.kolmafiaandroid.tools.MailFragment
import com.example.kolmafiaandroid.tools.MaximizerFragment
import com.example.kolmafiaandroid.tools.MeatFragment
import com.example.kolmafiaandroid.tools.MoodFragment
import com.example.kolmafiaandroid.tools.PreferencesFragment

/**
 * Registry of available tools under the Tools launcher.
 */
data class Tool(
    val id: String,
    val title: String,
    val subtitle: String,
    val open: (FragmentManager) -> Unit = {},
    /** If set, the tool opens this KoL page on the Game tab instead of a native screen. */
    val page: String? = null,
    /** Section heading in the Tools list. */
    val group: String = "More"
)

object ToolRegistry {
    /** Order of the section headings in the Tools list. */
    val groupOrder = listOf("Shopping", "Social", "Character", "Info and scripts", "Mazes", "More")

    val tools = mutableListOf<Tool>().apply {
        add(Tool(
            id = "scripts",
            title = "Script manager",
            subtitle = "Install, update and delete script projects (run and edit are on the Scripts tab)",
            open = { fm -> ScriptsFragment().show(fm, "scripts") },
            group = "Info and scripts"
        ))
        add(Tool(
            id = "mall",
            title = "Mall",
            subtitle = "Search the player mall and buy",
            open = { fm -> MallFragment.show(fm) },
            group = "Shopping"
        ))
        add(Tool(
            id = "craft",
            title = "Craft",
            subtitle = "Cook, mix, smith and combine what you can make",
            open = { fm -> CraftFragment.show(fm) },
            group = "Shopping"
        ))
        add(Tool(
            id = "npcshops",
            title = "NPC shops",
            subtitle = "Buy from the Mystic Bookshelf, Armory, Gnomish and other stores",
            open = { fm -> NpcShopsFragment.show(fm) },
            group = "Shopping"
        ))
        add(Tool(
            id = "coinmasters",
            title = "Coinmasters",
            subtitle = "Spend tokens, tickets and currencies on special items",
            open = { fm -> NpcShopsFragment.show(fm, true) },
            group = "Shopping"
        ))
        add(Tool(
            id = "clan",
            title = "Clan",
            subtitle = "Stash, fax machine, clan hall and whitelist",
            open = { fm -> ClanHubFragment.show(fm) },
            group = "Social"
        ))
        add(Tool(
            id = "profile",
            title = "Player lookup",
            subtitle = "Class, level, clan, ascensions and PvP rank of any player",
            open = { fm -> ProfileFragment.show(fm) },
            group = "Social"
        ))
        add(Tool(
            id = "modifiers",
            title = "Modifiers",
            subtitle = "Every bonus and penalty on your character right now",
            open = { fm -> ModifiersFragment.show(fm) },
            group = "Character"
        ))
        add(Tool(
            id = "gains",
            title = "Session gains",
            subtitle = "Items, meat and stats gained since you logged in",
            open = { fm -> GainsFragment.show(fm) },
            group = "Character"
        ))
        add(Tool(
            id = "store",
            title = "My store",
            subtitle = "Your mall listings: reprice, take back, add items",
            open = { fm -> StoreFragment.show(fm) },
            group = "Shopping"
        ))
        add(Tool(
            id = "items",
            title = "Items",
            subtitle = "Inventory, closet, storage, display case",
            open = { fm -> ItemsFragment.show(fm) },
            group = "Character"
        ))
        add(Tool(
            id = "meat",
            title = "Meat",
            subtitle = "Meat in hand, closet, and storage",
            open = { fm -> MeatFragment.show(fm) },
            group = "Character"
        ))
        add(Tool(
            id = "mail",
            title = "Mail",
            subtitle = "Read your inbox and send kmail",
            open = { fm -> MailHubFragment.show(fm) },
            group = "Social"
        ))
        add(Tool(
            id = "mushroom",
            title = "Mushroom plot",
            subtitle = "See the plot and forecast, pick or harvest",
            open = { fm -> MushroomFragment.show(fm) },
            group = "Character"
        ))
        add(Tool(
            id = "synthesize",
            title = "Synthesize",
            subtitle = "Sweet Synthesis: pick an effect, preview, do it",
            open = { fm -> SynthesizeFragment.show(fm) },
            group = "Character"
        ))
        add(Tool(
            id = "diet",
            title = "Food & drink",
            subtitle = "Diet planner: best food, drink and spleen for your turns, with next-item buttons",
            open = { fm -> DietFragment.show(fm) },
            group = "Character"
        ))
        add(Tool(
            id = "train",
            title = "Train familiar",
            subtitle = "Cake-Shaped Arena: train the familiar you have out",
            open = { fm -> TrainFragment.show(fm) },
            group = "Character"
        ))
        add(Tool(
            id = "buffrequest",
            title = "Request buffs",
            subtitle = "Buy buffs from a public buffbot",
            open = { fm -> BuffRequestFragment.show(fm) },
            group = "Shopping"
        ))
        add(Tool(
            id = "buffbot",
            title = "BuffBot",
            subtitle = "Sell buffs for meat: price list and run",
            open = { fm -> BuffBotFragment.show(fm) },
            group = "Social"
        ))
        add(Tool(
            id = "trophies",
            title = "Trophies",
            subtitle = "Your trophy case, in KoL's own page",
            page = "trophies.php",
            group = "Character"
        ))
        add(Tool(
            id = "logs",
            title = "Logs",
            subtitle = "View session logs and debug files",
            open = { fm -> LogsFragment.show(fm) },
            group = "Info and scripts"
        ))
        add(Tool(
            id = "preferences",
            title = "Preferences",
            subtitle = "Browse and edit KoLmafia preferences",
            open = { fm -> PreferencesFragment.show(fm) },
            group = "Info and scripts"
        ))
        add(Tool(
            id = "database",
            title = "Database",
            subtitle = "Look up items, monsters, skills, effects",
            open = { fm -> DatabaseFragment.show(fm) },
            group = "Info and scripts"
        ))
        add(Tool(
            id = "maximizer",
            title = "Maximizer",
            subtitle = "Optimize equipment with maximize command",
            open = { fm -> MaximizerFragment.show(fm) },
            group = "Info and scripts"
        ))
        fun page(id: String, title: String, subtitle: String, path: String) = add(Tool(
            id = id, title = title, subtitle = subtitle, group = "Mazes",
            open = { _ ->
                com.example.kolmafiaandroid.KolEngineHost.pendingWeb = path
                com.example.kolmafiaandroid.KolEngineHost.webRequests.tryEmit(path)
            }
        ))
                page("hedge", "Hedge maze", "Sorceress' Tower hedge maze solver", "hedgepuzzle.php")
        page("volcano", "Volcano maze", "Nemesis volcano cave maze solver", "volcanomaze.php")
    }
}