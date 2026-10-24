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
    private val iotmNames = """
1950 Vampire Vintner wine
2002 Mr. Store Catalog
a cute angel
A Guide to Burning Leaves
adventurer clone egg
airplane charter: Conspiracy Island
airplane charter: Dinseylandfill
airplane charter: Spring Break Beach
airplane charter: That 70s Volcano
airplane charter: The Glaciest
Alice's Army booster box
Apathargic Bandersnatch
April Shower Thoughts shield
Apriling band helmet
Archaeologist's Spade
assemble-it-yourself Leprecondo
astral badger
august scepter
Autumn-Aton
baby camelCalf
baby chest mimic
backup camera
bad penguin egg
Bag o' Tricks
bag of Iunion stones
Bagged Cargo Cultist Shorts
Baseball Diamond
Bastille Battalion control rig
Bastille Battalion control rig crate
bat wings
Beach Comb
Beach Comb Box
Better Shrooms and Gardens catalog
Bird-a-Day calendar
Black and White Apron Enrollment Form
black rosebud
Booke of Vampyric Knowledge
Boris's Helm
bottle of lovebug pheromones
bottle-rocket crossbow
Bottled green pixie
Bottled Vampire Vintner
box o' ghosts
box of bear arms
box of Familiar Jacks
Boxed Apriling band helmet
Boxed Archaeologist's Spade
Boxed august scepter
Boxed bat wings
Boxed Heartstone
Boxed Mayam Calendar
Boxed Sept-Ember Censer
Boxing Day care package
Buddy Bjorn
Build-a-City Gingerbread kit
Camp Scout backpack
can of Rain-Doh
candy cane sword cane
candy cornucopia
Cargo Cultist Shorts
Chateau Mantegna room key
Cincho de Mayo
Clan Carnival Game
Clan Floundry
Clan hot dog stand
Clan looking glass
Clan pool table
Clan shower
Clan speakeasy
clan underground fireworks shop
Clan VIP Lounge invitation
Clan VIP Lounge key
class five ecto-larva
closed-circuit phone system
Cold medicine cabinet
combat lover's locket
combat lover's locket lockbox
Comma Chameleon egg
Comprehensive Cartographic Compendium
Comprehensive Cartographic Compendium (well-read)
container of Spooky Putty
corked genie bottle
cosmic bowling ball
cotton candy cocoon
crimbo elfling
Crimbo P. R. E. S. S. I. E. (hatchling)
Crimbo sapling
Crown of Thrones
Cup of 13s
cursed magnifying glass
cursed monkey glove
cursed monkey's paw
CyberRealm keycode
dandy lion cub
Dark Jill-O-Lantern
Dark Jill-of-All-Trades
Daylight Shavings Helmet
deactivated nanobots
Deanimated reanimator's coffin
Dear Past Self Package
Deck of Every Card
Deflated inflatable dodecapede
designer sweatpants (new old stock)
detective school application
diabolic pizza cube
disconnected intergnat
Discontent™ Winter Garden Catalog
Discreetly-wrapped Eternity Codpiece
Distant Woods Getaway Brochure
DIY protonic accelerator kit
doppelshifter egg
Ed the Undying exhibit crate
Eight Days a Week Pill Keeper
Elvish sunglasses
emo roe
emotion chip
Eternal Car Battery
eternity codpiece
Everfull Dart Holster
fairy-worn boots
familiar scrapbook
FantasyRealm membership packet
fist turkey outline
floaty stone sphere
Folder Holder
Fourth of May Cosplay Saber
Fourth of May Cosplay Saber kit
Fresh can of paint
GameInformPowerDailyPro subscription card
genie bottle
glowing frisbee
God Lobster Egg
Granny Tood's Thanksgarden Catalog
Great Ball of Frozen Fire
Greatest American Pants
grey gosling
Grumpy Bumpkin's Pumpkin Seed Catalog
Guzzlr application
Guzzlr tablet
haiku katana
hand turkey outline
haunted doghouse
heart-shaped crate
Heartstone
hewn moon-rune spoon
hibernating robot reindeer
homeless hobo spirit
iceberglet
In-the-box spring shoes
industrial fire extinguisher
infant sandworm
Interesting Coin Inheritance Letter
Jarlsberg's pan
Jekyllin hide belt
jewel-eyed wizard hat
Juju Mojo Mask
June cleaver
Jurassic Parka
kitten burglar
KoLHS Pep Squad Box
Kramco Industries packing carton
Kremlin's Greatest Briefcase
lab-grown blood cubic zirconia
latte lovers club card
Latte lovers member's mug
Leprecondo
li'l orphan tot
LI-11 Motor Pool voucher
Libram of BRICKOs
Libram of Candy Heart Summoning
Libram of Divine Favors
Libram of Love Songs
Libram of Pulled Taffy
Libram of Resolutions
Lil' Doctor bag
Lil' Doctor bag (used)
little box of fireworks
Little Geneticist DNA-Splicing Lab
llama lama cria
Loathing Legion knife
Locked mumming trunk
LT&T telegraph office deed
lucky Tam O'Shanter
machine elf capsule
Make-Your-Own-Vampire-Fangs kit
March hat
Mayam Calendar
MayDay™ contract
Mayflower bouquet
McHugeLarge deluxe ski set
McPhee's Grimoire of Hilarious Object Summoning
meat shield toddler
mini kiwi
mini kiwi egg
miniature crystal ball
miniature gravy-covered maypole
miniscule temporal rip
Mint condition Lil' Doctor™ bag
mint condition magnifying glass
Mint Salton Pepper's Peppermint Seed Catalog
Mint-in-box Powerful Glove
model train set
Monodent of the Sea
Moping Artistic Goth Kid
moveable feast
mummified entombed cookbookbat
mumming trunk
My Own Pen Pal kit
mysterious chest
Möbius ring
Möbius ring box
naughty origami kit
navel ring of navel gazing
Neverending Party invitation envelope
New-in-box toy Cupid bow
New-You Club Membership Form
Olympic-sized Clan crate
Operation Patriot Shield
Order of the Green Thumb Order Form
organ grinder
orphan baby yeti
Our Daily Candles™ order form
Pack of Every Card
Packaged April Shower Thoughts Calendar
Packaged backup camera
Packaged cold medicine cabinet
Packaged Daylight Shavings Helmet
Packaged Everfull Dart Holster
Packaged familiar scrapbook
Packaged industrial fire extinguisher
Packaged June cleaver
Packaged Jurassic Parka
Packaged knock-off retro superhero cape
Packaged miniature crystal ball
Packaged model train set
Packaged Monodent of the Sea
packaged Pocket Professor
Packaged prismatic beret
Packaged Roman Candelabra
Packaged SpinMaster™ lathe
packet of mayfly bait
panicked kernel
pantogram
Pantsgiving
pasta wand loot box
peace turkey outline
Peridot of Peril
personal raindrop
Pete & Jackie's Dragon Tooth Emporium Catalog
pilgrim shield
PirateRealm membership packet
plastic pumpkin bucket
plastic vampire fangs
Pocket Meteor Guide
Pocket Professor memory chip
Pokéfam Guide to Capturing All of Them
portable Mayo Clinic
power seed
Powerful Glove
prismatic beret
pygmy bugbear shaman
Rain-Doh black box
red-spotted snapper roe
retrospecs
Retrospecs try-at-home kit
Rock Garden Guide
Roman Candelabra
rune-strewn spoon cocoon
S.I.T. Course Voucher
sane hatrack
scabbarded Sword of S Words
Schmalz's First Prize Beer
Scratch 'n' Sniff Sticker Tome
seal-clubbing club loot box
Sealed TakerSpace letter of Marque
Sept-Ember Censer
shortest-order cook
shrine to the Barrel god
Shrink-wrapped 2002 Mr. Store Catalog
Shrink-wrapped Cincho de Mayo
Shrink-wrapped Cup of 13s
shrunken head in a duffel bag
silk garter snake
sinistral homunculus
sleeping patriotic eagle
Small Medium
Sneaky Pete's leather jacket
Snow Suit
SongBoom™ BoomBox
SongBoom™ BoomBox Box
Sorcerers of the Shore Grimoire
Source terminal
Sp'n-Zor's Grimoire of "Tasteful" Gifts
space planula
Spacegate access badge
SpinMaster lathe
spooky rattling cigar box
spring shoes
squamous polyp
still grill
stinky cheese ball
stocking full of bones
stuffed-shirt scarecrow
suspicious package
suspicious stocking
sweet nutcracker
TakerSpace letter of Marque
tearaway pants
The Confiscator's Grimoire
The Smith's Tome
Thinknerd's Grimoire of Geeky Gifts
Thor's Pliers
Time-Spinner
tiny stillsuit
Tome of Clip Art
Tome of Snowcone Summoning
Tome of Sugar Shummoning
toy Cupid bow
Travoltan trousers
Unagnimated Gnome
unbreakable umbrella
Undamaged Unbreakable Umbrella
Undrilled cosmic bowling ball
Unoccupied sheep suit
Unopened Bird-a-Day calendar
Unopened diabolic pizza cube box
Unopened Eight Days a Week Pill Keeper
Unopened tiny stillsuit
Unpeeled Peridot of Peril
Unpowered Robortender
Untorn tearaway pants package
unwrapped knock-off retro superhero cape
V for Vivala mask
Vampire Vintner
vampyric cloake
vampyric cloake pattern
voter registration form
wax lips
Witchess Set
wizard action figure
Wrapped Baseball Diamond
Wrapped candy cane sword cane
X-32-F snowman crate
xo-skeleton-in-a-box
yellow puck
yeti in a travel cooler
yuletide troll chrysalis
Source terminal*
Spacegate*
portable Spacegate*
genie's*
Libram of*
Tome of*
Smith's Tome*
Thinknerd's*
Sp'n-Zor's*
McPhee's*
Sorcerers of the Shore*
Gygaxian*
Confiscator's*
Scratch 'n' Sniff*
Replica *
replica *
Crimbo P. R. E. S. S. I. E*
Pantsgiving*
Cursed monkey*
cursed monkey*
""".trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
    private fun norm(s: String) = android.text.Html.fromHtml(s, 0).toString().lowercase().filter { it.isLetterOrDigit() }
    private val exact = iotmNames.filter { !it.endsWith("*") }.map { norm(it) }.toHashSet()
    private val prefixes = iotmNames.filter { it.endsWith("*") }.map { norm(it) }

    private val states = Regex("^(corked|uncorked|empty|used|open|opened|unopened|unwrapped|packaged|boxed|wrapped|mint condition|mint-in-box|shrink-wrapped|replica|bagged|sealed|bottled|undamaged)\\s+|\\s*\\((open|used|empty|closed|unopened)\\)$", RegexOption.IGNORE_CASE)

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
