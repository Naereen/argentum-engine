package com.wingedsheep.gameserver.otaria

import com.wingedsheep.engine.otaria.OtariaCardKind
import com.wingedsheep.engine.otaria.OtariaEventCopyRule
import com.wingedsheep.engine.otaria.OtariaPileCard
import com.wingedsheep.engine.otaria.OtariaSetup
import com.wingedsheep.engine.otaria.OtariaSupplies
import com.wingedsheep.engine.otaria.OtariaTreasure
import com.wingedsheep.engine.otaria.OtariaTreasureToken
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.mtg.sets.otaria.OtariaEventEffects
import com.wingedsheep.sdk.core.Color

/**
 * Physical supply from the v0.4 installation annex, plus the confirmed 22nd boss. Event names
 * identify the supplied proxies; some event effects, encounter rules, and ordinary card definitions
 * are not implemented yet.
 */
internal object OtariaSuppliesCatalog {
    fun create(): OtariaSupplies = OtariaSupplies(
        encounterSupport = OtariaEncounterSupportCatalog.table,
        bosses = listOf(
            "Aboshan, Cephalid Emperor", "Akroma, Angel of Wrath", "Ambassador Laquatus",
            "Chainer, Dementia Master", "Commander Eesha", "Jareth, Leonine Titan",
            "Kamahl, Fist of Krosa", "Kamahl, Pit Fighter", "Llawan, Cephalid Empress", "Lieutenant Kirtar",
            "Major Teroh", "Cabal Patriarch", "Phage the Untouchable", "Rorix Bladewing",
            "Silvos, Rogue Elemental", "Essence Sliver", "Brood Sliver", "Magma Sliver",
            "Synapse Sliver", "Toxin Sliver", "Sliver Overlord", "Visara the Dreadful",
        ),
        discoveries = 22,
        treasures = buildList {
            repeat(2) { add(OtariaTreasure.Card("Ark of Blight")) }
            add(OtariaTreasure.Card("Dream Chisel"))
            repeat(4) { add(OtariaTreasure.Token(OtariaTreasureToken.SCRAP)) }
            repeat(2) { add(OtariaTreasure.Card("Proteus Machine")) }
            addAll(listOf(
                OtariaTreasure.Card("Shadowblood Egg"),
                OtariaTreasure.Card("Skycloud Egg"),
                OtariaTreasure.Card("Mossfire Egg"),
                OtariaTreasure.Card("Sungrass Egg"),
                OtariaTreasure.Card("Darkwater Egg"),
                OtariaTreasure.Card("Cryptic Gateway"),
                OtariaTreasure.Card("Riptide Replicator"),
            ))
            repeat(5) { add(OtariaTreasure.Token(OtariaTreasureToken.ROCK)) }
            add(OtariaTreasure.Card("Stabilizer"))
            // The annex's "Tablette de l'ascendance" refers to Slate of Ancestry.
            add(OtariaTreasure.Card("Slate of Ancestry"))
        },
        colorPiles = mapOf(
            Color.GREEN to pile(
                spells = listOf(
                    "Accelerated Mutation", "Break Asunder", "Dragon Fangs",
                    "Druid's Call", "Living Wish", "Mythic Proportions",
                    "Narcissism", "Naturalize", "Sprouting Vines", "Tribal Unity",
                ),
                creatures = listOf(
                    "Barkhide Mauler", "Bloodline Shaman", "Brawn",
                    "Broodhatch Nantuko", "Elven Riders", "Elvish Aberration",
                    "Elvish Pathcutter", "Elvish Warrior", "Enormous Baloth",
                    "Krosan Tusker", "Krosan Vorine", "Krosan Warchief",
                    "Leaf Dancer", "Leaf Dancer", "Nantuko Mentor",
                    "Nantuko Vigilante", "Nantuko Vigilante", "Needleshot Gourna",
                    "Nimble Mongoose", "Patron of the Wild", "Patron of the Wild",
                    "Phantom Nantuko", "Possessed Centaur", "Root Sliver",
                    "Serpentine Basilisk", "Seton's Scout", "Spitting Gourna",
                    "Springing Tiger", "Stag Beetle", "Stonewood Invoker",
                    "Symbiotic Beast", "Timberwatch Elf", "Titanic Bulvox",
                    "Treespring Lorian", "Werebear", "Wirewood Savage",
                ),
                events = listOf(
                    "Ev. Bond d'aether éloigné G", "Ev. Bond d'aether proche G",
                    "Ev. Marchant itinérant G3", "Ev. Marchant itinérant G5",
                    "Ev. Naturalisation", "Ev. Naturalisation",
                    "Ev. Symbiotiseur itinérant", "Ev. Symbiotiseur itinérant",
                ),
            ),
            Color.RED to pile(
                spells = listOf(
                    "Burning Wish", "Carbonize", "Custody Battle", "Dragon Breath",
                    "Firebolt", "Gratuitous Violence", "Pyromania", "Scorching Missile",
                    "Solar Blast", "Torrent of Fire",
                ),
                creatures = listOf(
                    "Anger", "Barbarian Lunatic", "Barbarian Lunatic",
                    "Blade Sliver", "Bloodstoke Howler", "Chartooth Cougar",
                    "Crested Craghorn", "Crested Craghorn", "Dragon Mage",
                    "Dwarven Grunt", "Dwarven Grunt", "Embermage Goblin",
                    "Flamewave Invoker", "Fledgling Dragon", "Goblin Lookout",
                    "Goblin Lookout", "Goblin Machinist", "Goblin Sky Raider",
                    "Goblin Sledder", "Goblin Warchief", "Goretusk Firebeast",
                    "Halberdier", "Macetail Hystrodon", "Pardic Collaborator",
                    "Pardic Swordsmith", "Possessed Barbarian", "Ridgetop Raptor",
                    "Shaleskin Plower", "Skirk Marauder", "Skirk Marauder",
                    "Skirk Outrider", "Skirk Outrider", "Skittish Valesk",
                    "Thoughtbound Primoc", "Unstable Hulk", "Warbreak Trumpeter",
                ),
                events = listOf(
                    "Ev. Bond d'aether éloigné R", "Ev. Bond d'aether proche R",
                    "Ev. Marchant itinérant R3", "Ev. Marchant itinérant R5",
                    "Ev. Pyroclasme", "Ev. Pyroclasme",
                    "Ev. Vœux Gobelin", "Ev. Vœux Gobelin",
                ),
            ),
            Color.BLACK to pile(
                spells = listOf(
                    "Coffin Purge", "Death Wish", "Diabolic Tutor", "Dragon Shadow",
                    "Morbid Hunger", "Morgue Theft", "Mortiphobia", "Oversold Cemetery",
                    "Skulltap", "Smother",
                ),
                creatures = listOf(
                    "Anurid Murkdiver", "Aphetto Exterminator", "Bane of the Living",
                    "Boneknitter", "Cabal Archon", "Dark Supplicant",
                    "Death's-Head Buzzard", "Disciple of Malice", "Dripping Dead",
                    "Faceless Butcher", "Festering Goblin", "Festering Goblin",
                    "Filth", "Haunted Cadaver", "Hypnox",
                    "Nantuko Husk", "Noxious Ghoul", "Prowling Pangolin",
                    "Repentant Vampire", "Screeching Buzzard", "Severed Legion",
                    "Skinthinner", "Skinthinner", "Smokespew Invoker",
                    "Sootfeather Flock", "Soul Collector", "Soul Scourge",
                    "Spectral Sliver", "Spined Basher", "Treacherous Werewolf",
                    "Twisted Abomination", "Undead Warchief", "Vengeful Dead",
                    "Vile Deacon", "Whispering Shade", "Zombie Brute",
                ),
                events = listOf(
                    "Ev. Bond d'aether éloigné B", "Ev. Bond d'aether proche B",
                    "Ev. Marchant itinérant B3", "Ev. Marchant itinérant B5",
                    "Ev. Disciple de la Coterie itinérant", "Ev. Disciple de la Coterie itinérant",
                    "Ev. Infestation", "Ev. Infestation",
                ),
            ),
            Color.BLUE to pile(
                spells = listOf(
                    "Compulsion", "Concentrate", "Cunning Wish", "Deep Analysis",
                    "Dragon Wings", "Hindering Touch", "Liquify", "Meddle",
                    "Rush of Knowledge", "Time Stretch",
                ),
                creatures = listOf(
                    "Ascending Aven", "Aven Windreader", "Balshan Collaborator",
                    "Cephalid Aristocrat", "Cephalid Inkshrouder", "Clone",
                    "Coast Watcher", "Covert Operative", "Dreamwinder",
                    "Echo Tracer", "Echo Tracer", "Glintwing Invoker",
                    "Graxiplon", "Keeneye Aven", "Keeneye Aven",
                    "Mercurial Kite", "Mercurial Kite", "Mirror Wall",
                    "Mirror Wall", "Mistform Seaswift", "Mistform Shrieker",
                    "Mistform Stalker", "Mistform Wakecaster", "Mistform Warchief",
                    "Possessed Aven", "Primoc Escapee", "Quicksilver Dragon",
                    "Riptide Mangler", "Scornful Egotist", "Screaming Seahawk",
                    "Shifting Sliver", "Shoreline Ranger", "Slipstream Eel",
                    "Thought Nibbler", "Thought Nibbler", "Wonder",
                ),
                events = listOf(
                    "Ev. Bond d'aether éloigné U", "Ev. Bond d'aether proche U",
                    "Ev. Marchant itinérant U3", "Ev. Marchant itinérant U5",
                    "Ev. Blocage cérébral", "Ev. Blocage cérébral",
                    "Ev. Nouvelle Idée", "Ev. Nouvelle Idée",
                ),
            ),
            Color.WHITE to pile(
                spells = listOf(
                    "Astral Steel", "Aurification", "Battle Screech", "Dragon Scales",
                    "Golden Wish", "Hypochondria", "Ray of Revelation", "Renewed Faith",
                    "Shelter", "Spiritualize",
                ),
                creatures = listOf(
                    "Angel of Retribution", "Aven Farseer", "Aven Flock",
                    "Aven Liberator", "Aven Redeemer", "Beloved Chaplain",
                    "Catapult Squad", "Daru Cavalier", "Daru Lancer",
                    "Daru Lancer", "Daru Warchief", "Daunting Defender",
                    "Disciple of Grace", "Dive Bomber", "Exalted Angel",
                    "Gustcloak Harrier", "Gustcloak Harrier", "Karona's Zealot",
                    "Lowland Tracker", "Lowland Tracker", "Mystic Zealot",
                    "Noble Templar", "Phantom Flock", "Possessed Nomad",
                    "Reborn Hero", "Resilient Wanderer", "Silver Knight",
                    "Starlight Invoker", "Valor", "Vigilant Sentry",
                    "Ward Sliver", "Whipgrass Entangler", "White Knight",
                    "Wingbeat Warrior", "Zealous Inquisitor", "Zealous Inquisitor",
                ),
                events = listOf(
                    "Ev. Bond d'aether éloigné W", "Ev. Bond d'aether proche W",
                    "Ev. Marchant itinérant W3", "Ev. Marchant itinérant W5",
                    "Ev. Frappe Concertée", "Ev. Frappe Concertée",
                    "Ev. Soigneur itinérant", "Ev. Soigneur itinérant",
                ),
            ),
        ),
    )

    /** Event proxies still need a separate check; they are not ordinary card definitions. */
    fun missingCardDefinitions(registry: CardRegistry): List<String> {
        val supplies = create()
        val names = supplies.bosses +
            supplies.treasures.filterIsInstance<OtariaTreasure.Card>().map { it.name } +
            supplies.colorPiles.values.flatten()
                .filter { it.kind == OtariaCardKind.CREATURE || it.kind == OtariaCardKind.NONCREATURE }
                .map { it.name } +
            listOf(OtariaSetup.MIRARI, "Forest", "Mountain", "Swamp", "Island", "Plains") +
            OtariaEncounterSupportCatalog.referencedCardNames()
        return names.distinct().filterNot(registry::hasCard).sorted()
    }

    private fun pile(spells: List<String>, creatures: List<String>, events: List<String>): List<OtariaPileCard> =
        spells.map { OtariaPileCard(it, OtariaCardKind.NONCREATURE) } +
            creatures.map { OtariaPileCard(it, OtariaCardKind.CREATURE) } +
            events.map {
                OtariaPileCard(
                    it,
                    OtariaCardKind.EVENT,
                    OtariaEventEffects.forName(it),
                    if (it == "Ev. Blocage cérébral") OtariaEventCopyRule.EACH_SPELL_CAST_THIS_TURN else null,
                )
            } +
            OtariaPileCard(OtariaSetup.DISCOVERY, OtariaCardKind.DISCOVERY)
}
