package com.wingedsheep.gameserver.otaria

import com.wingedsheep.engine.otaria.OtariaEncounterSupport
import com.wingedsheep.engine.otaria.OtariaEncounterSupportTable
import com.wingedsheep.engine.otaria.OtariaExplorationSource
import com.wingedsheep.engine.otaria.OtariaReinforcementContent
import com.wingedsheep.engine.otaria.OtariaReinforcementCount
import com.wingedsheep.engine.otaria.OtariaReinforcementDestination
import com.wingedsheep.engine.otaria.OtariaReinforcementGroup
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Subtype

/**
 * Encounter support from the v0.4 installation annex. Physical reinforcement cards come from an
 * unlimited separate stock; looking them up here does not remove cards from an exploration pile.
 */
internal object OtariaEncounterSupportCatalog {
    private val redGoblins = token(Color.RED, 1, 1, die(4), setOf(Subtype.GOBLIN))
    private val blackClerics = token(Color.BLACK, 1, 1, die(4), setOf(Subtype.HUMAN, Subtype.CLERIC))
    private val whiteSoldiers = token(Color.WHITE, 1, 1, die(4), setOf(Subtype.SOLDIER))

    val colors: Map<Color, Map<String, OtariaEncounterSupport>> = mapOf(
        Color.RED to mapOf(
            "Goblin Sledder" to support(redGoblins),
            "Goblin Lookout" to support(redGoblins),
            "Goblin Taskmaster" to support(redGoblins),
            "Embermage Goblin" to support(card("Embermage Goblin", 3)),
            "Skirk Outrider" to support(token(Color.GREEN, 3, 3, fixed(1), setOf(Subtype.BEAST))),
            "Blade Sliver" to support(card("Hunter Sliver", 2)),
            "Pardic Swordsmith" to support(cardInHand("Mountain")),
            "Goblin Warchief" to support(redGoblins),
        ),
        Color.BLACK to mapOf(
            "Severed Legion" to support(cappedCopies("Severed Legion")),
            "Festering Goblin" to support(cappedCopies("Festering Goblin")),
            "Vile Deacon" to support(blackClerics),
            "Undead Warchief" to support(token(Color.BLACK, 2, 2, die(2), setOf(Subtype.ZOMBIE))),
            "Dark Supplicant" to support(copyTokens(4), cardInHand("Scion of Darkness")),
            "Cabal Archon" to support(blackClerics),
            "Spectral Sliver" to support(card("Crypt Sliver", 2)),
            "Noxious Ghoul" to support(token(Color.BLACK, 2, 2, die(4, modifier = -1), setOf(Subtype.ZOMBIE))),
        ),
        Color.GREEN to mapOf(
            "Barkhide Mauler" to support(card("Barkhide Mauler", 2)),
            "Elven Riders" to support(copyTokens(3)),
            "Root Sliver" to support(card("Quick Sliver", 2)),
            "Nantuko Mentor" to support(card("Anurid Barkripper", 1)),
            "Timberwatch Elf" to support(token(Color.GREEN, 1, 1, die(4), setOf(Subtype.ELF, Subtype.WARRIOR))),
        ),
        Color.WHITE to mapOf(
            "Aven Farseer" to OtariaEncounterSupport(faceUpTriggerIgnoresRange = true),
            "Daru Cavalier" to support(card("Daru Cavalier", 3)),
            "Daru Warchief" to support(whiteSoldiers),
            "Catapult Squad" to support(token(Color.WHITE, 1, 1, die(4, modifier = 1), setOf(Subtype.SOLDIER))),
            "Whipgrass Entangler" to support(token(Color.WHITE, 1, 1, die(4), setOf(Subtype.HUMAN, Subtype.CLERIC))),
            "Resilient Wanderer" to support(cardInHand("Plains")),
            "Ward Sliver" to support(card("Plated Sliver", 2)),
        ),
        Color.BLUE to mapOf(
            "Screaming Seahawk" to support(card("Screaming Seahawk", 3)),
            "Cephalid Inkshrouder" to support(cardInHand("Island")),
            "Shifting Sliver" to support(card("Mistform Sliver", 2)),
        ),
    )

    val stars: Map<String, OtariaEncounterSupport> = mapOf(
        "Essence Sliver" to support(card("Ward Sliver", 2), card("Plated Sliver", 2)),
        "Synapse Sliver" to support(card("Shifting Sliver", 2), card("Mistform Sliver", 2)),
        "Brood Sliver" to support(card("Root Sliver", 2), card("Quick Sliver", 2)),
        "Kamahl, Fist of Krosa" to support(card("Forest", 6)),
        "Cabal Patriarch" to support(token(Color.BLACK, 2, 2, die(4, minimum = 2), setOf(Subtype.ZOMBIE))),
        "Toxin Sliver" to support(card("Spectral Sliver", 2), card("Crypt Sliver", 2)),
        "Magma Sliver" to support(card("Blade Sliver", 2), card("Hunter Sliver", 2)),
        "Aboshan, Cephalid Emperor" to support(token(Color.BLUE, 1, 1, die(4), setOf(Subtype.OCTOPUS, Subtype.WIZARD))),
    )

    val table = OtariaEncounterSupportTable(colors, stars)

    fun forCard(source: OtariaExplorationSource, cardName: String): OtariaEncounterSupport? =
        table.forCard(source, cardName)

    fun referencedCardNames(): Set<String> = buildSet {
        val groups = listOf(stars) + colors.values
        for (rules in groups) {
            addAll(rules.keys)
            for (support in rules.values) {
                for (reinforcement in support.reinforcements) {
                    val card = reinforcement.content as? OtariaReinforcementContent.Card
                    if (card != null) add(card.name)
                }
            }
        }
    }

    private fun fixed(count: Int) = OtariaReinforcementCount.Fixed(count)

    private fun die(sides: Int, modifier: Int = 0, minimum: Int = 0) =
        OtariaReinforcementCount.Die(sides, modifier, minimum)

    private fun support(vararg groups: OtariaReinforcementGroup) =
        OtariaEncounterSupport(reinforcements = groups.toList())

    private fun card(name: String, count: Int) =
        OtariaReinforcementGroup(OtariaReinforcementContent.Card(name), fixed(count))

    private fun cardInHand(name: String) = OtariaReinforcementGroup(
        OtariaReinforcementContent.Card(name, OtariaReinforcementDestination.HAND), fixed(1),
    )

    private fun cappedCopies(name: String) = OtariaReinforcementGroup(
        OtariaReinforcementContent.Card(name), die(4), maximumCopiesOnBattlefield = 4,
    )

    private fun copyTokens(count: Int) =
        OtariaReinforcementGroup(OtariaReinforcementContent.EncounteredCardCopyToken, fixed(count))

    private fun token(
        color: Color,
        power: Int,
        toughness: Int,
        count: OtariaReinforcementCount,
        subtypes: Set<Subtype>,
    ) = OtariaReinforcementGroup(
        OtariaReinforcementContent.CreatureToken(setOf(color), subtypes, power, toughness),
        count,
    )
}
