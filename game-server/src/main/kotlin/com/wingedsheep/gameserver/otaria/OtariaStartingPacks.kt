package com.wingedsheep.gameserver.otaria

import com.wingedsheep.engine.otaria.OtariaStartingLandSource
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.registry.PrintingRegistry
import com.wingedsheep.gameserver.jumpstart.JumpstartPacks
import com.wingedsheep.sdk.model.CardEntry
import com.wingedsheep.sdk.model.Printing
import kotlin.random.Random

enum class OtariaStartingColor {
    WHITE, BLUE, BLACK, RED, GREEN, OTHER,
}

data class OtariaPlaytestDeck(
    val id: String,
    val setCode: String,
    val theme: String,
    val color: OtariaStartingColor,
    val cards: Map<String, Int>,
    val cardEntries: List<CardEntry>,
    val startingLands: List<String>,
    val startingLandEntries: List<CardEntry>,
    val startingLandSource: OtariaStartingLandSource,
)

/** Reuses the published paper packs, never the exploration supplies or generated substitutes. */
internal class OtariaStartingPacks(
    cards: CardRegistry,
    private val printings: PrintingRegistry? = null,
) {
    val decks = listOf("J22", "J25").flatMap { set ->
        JumpstartPacks.listsFor(set).map { (variant, names) ->
            val definitions = names.map(cards::requireCard)
            val basics = definitions.filter { it.typeLine.isBasicLand }
            val colors = basics.mapNotNull { basicColors[it.name] }.distinct()
            val color = colors.singleOrNull() ?: OtariaStartingColor.OTHER
            val separateStock = basics.isEmpty()
            val startingLands = if (separateStock) {
                cards.requireCard("Wastes")
                listOf("Wastes", "Wastes")
            } else {
                // A multicolor booster starts with two different basics when it has them.
                val distinct = basics.distinctBy { it.name }
                (if (distinct.size >= 2) distinct else basics).take(2).map { it.name }
            }
            check(startingLands.size == 2) { "$set $variant needs two starting basic lands" }
            val cardEntries = names.map { name -> CardEntry(name, matchingPrinting(set, name)?.ref) }
            val startingLandEntries = startingLands.map { name -> CardEntry(name, matchingPrinting(set, name)?.ref) }
            OtariaPlaytestDeck(
                id = "$set:$variant",
                setCode = set,
                theme = variant.substringBefore(" ("),
                color = color,
                cards = names.groupingBy { it }.eachCount(),
                cardEntries = cardEntries,
                startingLands = startingLands,
                startingLandEntries = startingLandEntries,
                startingLandSource = if (separateStock) OtariaStartingLandSource.SEPARATE_STOCK else OtariaStartingLandSource.DECK,
            )
        }
    }

    private fun matchingPrinting(setCode: String, name: String): Printing? =
        printings?.printingsOf(name)
            ?.filter { it.setCode.equals(setCode, ignoreCase = true) }
            ?.sortedWith(compareBy<Printing> { it.isAlternateFrame }.thenBy { it.collectorNumber })
            ?.firstOrNull()

    fun choose(color: OtariaStartingColor?, id: String?, random: Random): OtariaPlaytestDeck {
        if (id != null) {
            val deck = requireNotNull(decks.find { it.id == id }) { "Unknown Jumpstart booster: $id" }
            require(color == null || deck.color == color) { "The chosen booster does not match the starting color" }
            return deck
        }
        val chosenColor = color ?: OtariaStartingColor.entries.random(random)
        return decks.filter { it.color == chosenColor }.random(random)
    }

    companion object {
        private val basicColors = mapOf(
            "Plains" to OtariaStartingColor.WHITE,
            "Island" to OtariaStartingColor.BLUE,
            "Swamp" to OtariaStartingColor.BLACK,
            "Mountain" to OtariaStartingColor.RED,
            "Forest" to OtariaStartingColor.GREEN,
            "Snow-Covered Plains" to OtariaStartingColor.WHITE,
            "Snow-Covered Island" to OtariaStartingColor.BLUE,
            "Snow-Covered Swamp" to OtariaStartingColor.BLACK,
            "Snow-Covered Mountain" to OtariaStartingColor.RED,
            "Snow-Covered Forest" to OtariaStartingColor.GREEN,
        )
    }
}
