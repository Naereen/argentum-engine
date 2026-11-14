package com.wingedsheep.engine.otaria

import com.wingedsheep.engine.core.CardEntityFactory
import com.wingedsheep.engine.core.CardsDrawnEvent
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.GameInitializer
import com.wingedsheep.engine.core.InitializationResult
import com.wingedsheep.engine.core.OtariaStartingLandsPlacedEvent
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.registry.PrintingRegistry
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.PlayerComponent
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.CardEntry
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.model.Rarity

enum class OtariaStartingDeckKind {
    CONSTRUCTED,
    JUMPSTART,
}

enum class OtariaStartingLandSource {
    DECK,
    SEPARATE_STOCK,
}

data class OtariaStartingPlayer(
    val player: PlayerConfig,
    val startingBasicLands: List<CardEntry>,
    val deckKind: OtariaStartingDeckKind = OtariaStartingDeckKind.CONSTRUCTED,
    val startingLandSource: OtariaStartingLandSource = OtariaStartingLandSource.DECK,
)

/**
 * Initializes opening cards before the first turn. A host must collect each player's land choices
 * and supply a seed; exposing the mode also requires its turn and encounter rules.
 */
class OtariaGameInitializer(
    private val cards: CardRegistry,
    private val printings: PrintingRegistry? = null,
) {
    fun initialize(
        players: List<OtariaStartingPlayer>,
        supplies: OtariaSupplies,
        seed: Long,
    ): InitializationResult {
        val firstSeat = OtariaSeating.startingPlayerIndex(players.size, seed)
        val explicitIds = players.mapNotNull { it.player.playerId }
        require(explicitIds.distinct().size == explicitIds.size) { "Player IDs must be distinct" }
        require(explicitIds.none { it.value.matches(Regex("e[1-9][0-9]*")) }) {
            "Explicit player IDs must not use the game-generated entity ID namespace"
        }
        players.forEach(::validateDeck)

        val initialized = GameInitializer(cards, printings).initializeGame(
            GameConfig(
                players = players.map { it.player.copy(startingLife = 15) },
                startingHandSize = 0,
                skipMulligans = true,
                startingPlayerIndex = firstSeat,
                seed = seed,
            ),
        )
        var state = initialized.state
        require(initialized.playerIds.distinct().size == players.size &&
            initialized.playerIds.all { state.getEntity(it)?.has<PlayerComponent>() == true }) {
            "Player IDs conflict with game-generated entity IDs"
        }
        val events = initialized.events.filterNot { it is CardsDrawnEvent && it.count == 0 }.toMutableList()
        for ((index, selection) in players.withIndex()) {
            val playerId = initialized.playerIds[index]
            val library = ZoneKey(playerId, Zone.LIBRARY)
            val battlefield = ZoneKey(playerId, Zone.BATTLEFIELD)
            val placed = mutableListOf<EntityId>()
            for (entry in selection.startingBasicLands) {
                val definition = cards.requireCard(entry.name)
                if (selection.startingLandSource == OtariaStartingLandSource.SEPARATE_STOCK) {
                    val (cardId, nextState) = state.newEntity()
                    state = nextState.withEntity(
                        cardId, CardEntityFactory.create(definition, playerId, entry.printing, printings),
                    ).addToZone(battlefield, cardId)
                    placed.add(cardId)
                    continue
                }
                val expectedCard = requireNotNull(
                    CardEntityFactory.create(definition, playerId, entry.printing, printings).get<CardComponent>(),
                )
                val cardId = state.getZone(library).firstOrNull { id ->
                    state.getEntity(id)?.get<CardComponent>() == expectedCard
                } ?: error("Chosen starting land was not initialized: ${entry.name}")
                state = state.moveToZone(cardId, library, battlefield)
                placed.add(cardId)
            }
            events.add(OtariaStartingLandsPlacedEvent(playerId, placed))
        }
        for (playerId in initialized.playerIds) {
            val (afterDraw, drawEvents) = GameInitializer.drawCards(state, playerId, 5)
            state = afterDraw
            events.addAll(drawEvents)
        }
        val setup = OtariaSetup.initialize(state, supplies)
        return initialized.copy(state = setup.state, events = events + setup.events)
    }

    private fun validateDeck(selection: OtariaStartingPlayer) {
        val deck = selection.player.deck
        require(deck.commanders.isEmpty() && selection.player.commanderCardNames.isEmpty()) {
            "An Otaria starting deck cannot have commanders"
        }
        val entries = deck.cardEntries.ifEmpty { deck.cards.map { CardEntry(it) } }
        require(entries.size == 20) { "An Otaria starting deck must contain 20 cards" }
        val definitions = entries.map { entry ->
            val definition = cards.requireCard(entry.name)
            if (entry.printing != null) rarity(entry, definition)
            entry to definition
        }
        val availableBasics = definitions.filter { it.second.typeLine.isBasicLand }.map { it.first }.toMutableList()
        require(selection.startingBasicLands.size == 2) { "Choose exactly two starting basic lands" }
        for (land in selection.startingBasicLands) {
            if (selection.startingLandSource == OtariaStartingLandSource.SEPARATE_STOCK) {
                require(selection.deckKind == OtariaStartingDeckKind.JUMPSTART) {
                    "Separate-stock starting lands require a Jumpstart deck"
                }
                val definition = cards.requireCard(land.name)
                require(definition.typeLine.isBasicLand) { "Starting land must be basic: ${land.name}" }
                if (land.printing != null) rarity(land, definition)
            } else {
                require(availableBasics.remove(land)) { "Chosen basic land is not available in the deck: ${land.name}" }
            }
        }
        if (selection.deckKind == OtariaStartingDeckKind.JUMPSTART) return

        val lands = definitions.filter { it.second.typeLine.isLand }
        require(lands.size == 8) { "A constructed Otaria starting deck must contain eight lands" }
        require(lands.count { !it.second.typeLine.isBasicLand } <= 3) {
            "A constructed Otaria starting deck permits at most three nonbasic lands"
        }
        val nonBasics = definitions.filterNot { it.second.typeLine.isBasicLand }.map { it.second.name }
        require(nonBasics.distinct().size == nonBasics.size) {
            "Only basic lands may have multiple copies in a constructed Otaria starting deck"
        }
        val spellRarities = definitions.filterNot { it.second.typeLine.isLand }
            .map { (entry, definition) -> rarity(entry, definition) }
        require(spellRarities.count { it == Rarity.RARE || it == Rarity.MYTHIC } == 1 &&
            spellRarities.count { it == Rarity.UNCOMMON } == 4 &&
            spellRarities.count { it == Rarity.COMMON } == 7) {
            "A constructed Otaria starting deck needs one rare/mythic, four uncommons, and seven commons"
        }
    }

    private fun rarity(entry: CardEntry, definition: CardDefinition): Rarity {
        val ref = entry.printing ?: return definition.metadata.rarity
        val printing = printings?.getPrinting(ref)
            ?: throw IllegalArgumentException("Starting deck printing not found: $ref")
        require(printing.name == definition.name) { "Printing $ref does not match ${definition.name}" }
        return printing.rarity
    }
}
