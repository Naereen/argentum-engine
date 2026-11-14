package com.wingedsheep.engine.otaria

import com.wingedsheep.engine.core.ExecutionResult
import com.wingedsheep.engine.core.LibraryShuffledEvent
import com.wingedsheep.engine.core.OtariaPreparedEvent
import com.wingedsheep.engine.core.ShuffleCause
import com.wingedsheep.engine.handlers.PredicateEvaluator
import com.wingedsheep.engine.handlers.effects.library.LibraryRevealUtils
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.LibraryPositionAnchor
import com.wingedsheep.engine.state.components.identity.OwnerComponent
import com.wingedsheep.engine.state.components.identity.RevealedToComponent
import com.wingedsheep.engine.state.components.identity.TokenComponent
import com.wingedsheep.engine.view.Visibility
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId

/**
 * Internal preparation transition. The host must enforce the preparation phase and present the
 * reserve choice before invoking it.
 */
internal class OtariaPreparation(
    cardRegistry: CardRegistry,
    predicateEvaluator: PredicateEvaluator,
) {
    private val visibility = Visibility(cardRegistry, conditionEvaluator = predicateEvaluator.conditions)

    fun prepare(state: GameState, playerId: EntityId, selectedCards: List<EntityId>): ExecutionResult {
        val mode = state.otaria ?: return ExecutionResult.error(state, "This is not an Otaria game")
        if (state.gameOver) return ExecutionResult.error(state, "The game is already over")
        if (state.activePlayerId != playerId || playerId !in state.activePlayers) {
            return ExecutionResult.error(state, "Only the active player can prepare")
        }
        if (state.pendingDecision != null || mode.pendingExploration != null || state.stack.isNotEmpty()) {
            return ExecutionResult.error(state, "Resolve the pending decision, exploration or stack first")
        }
        val player = mode.players[playerId]
            ?: return ExecutionResult.error(state, "The player is not on the Otaria board")
        if (selectedCards.size != selectedCards.toSet().size) {
            return ExecutionResult.error(state, "Choose each reserve card at most once")
        }
        for (cardId in selectedCards) {
            if (player.reserve.count { it == cardId } != 1 ||
                mode.players.any { (otherId, other) -> otherId != playerId && cardId in other.reserve }
            ) {
                return ExecutionResult.error(state, "The selected card is not exclusively in your reserve")
            }
            val container = state.getEntity(cardId)
                ?: return ExecutionResult.error(state, "The selected reserve card does not exist")
            if (container.get<CardComponent>()?.ownerId != playerId ||
                container.get<OwnerComponent>()?.playerId != playerId ||
                container.has<TokenComponent>()
            ) {
                return ExecutionResult.error(state, "The selected reserve object is not your card")
            }
            if (state.zones.values.any { cardId in it } || cardId in state.stack) {
                return ExecutionResult.error(state, "The selected reserve card is already in a game zone")
            }
        }

        val libraryZone = ZoneKey(playerId, Zone.LIBRARY)
        val library = state.getZone(libraryZone)
        val known = library.filter { visibility.isCardIdentityVisibleTo(state, libraryZone, it, playerId) }.toSet()
        val allKnown = known.size == library.size
        val preserved = library.mapIndexedNotNull { index, cardId ->
            if (cardId !in known) return@mapIndexedNotNull null
            val anchor = if (allKnown) LibraryPositionAnchor.TOP else {
                state.getEntity(cardId)?.get<RevealedToComponent>()?.libraryPositionAnchors?.get(playerId)
                    ?: when (index) {
                        0 -> LibraryPositionAnchor.TOP
                        library.lastIndex -> LibraryPositionAnchor.BOTTOM
                        else -> null
                    }
            } ?: return@mapIndexedNotNull null
            PreservedCard(cardId, index, anchor)
        }
        val byPosition = buildMap {
            var nextPosition = library.size + selectedCards.size
            for (card in preserved.asReversed()) {
                val destination = when (card.anchor) {
                    LibraryPositionAnchor.TOP, LibraryPositionAnchor.BOTH -> card.index
                    LibraryPositionAnchor.BOTTOM -> minOf(card.index + selectedCards.size, nextPosition - 1)
                }
                check(destination >= card.index && destination < nextPosition) {
                    "Known library positions must retain their original order"
                }
                put(destination, card.id)
                nextPosition = destination
            }
        }

        var current = state
        for (card in preserved) {
            val audience = state.turnOrder.filter {
                visibility.isCardIdentityVisibleTo(state, libraryZone, card.id, it)
            }
            val alreadyKnown = state.getEntity(card.id)?.get<RevealedToComponent>()?.playerIds.orEmpty()
            val newlyKnown = audience.filterNot { it in alreadyKnown }
            if (newlyKnown.isNotEmpty()) {
                current = LibraryRevealUtils.markRevealed(
                    current, listOf(card.id), newlyKnown,
                    if (card.index == 0) LibraryPositionAnchor.TOP else null,
                )
            }
            if (allKnown) {
                val observers = current.getEntity(card.id)?.get<RevealedToComponent>()?.playerIds.orEmpty()
                current = LibraryRevealUtils.markRevealed(
                    current, listOf(card.id), observers, LibraryPositionAnchor.TOP,
                )
            }
        }
        val protectedIds = preserved.mapTo(mutableSetOf()) { it.id }
        val mixedCards = library.filterNot { it in protectedIds } + selectedCards
        current = LibraryRevealUtils.clearReveals(current, mixedCards)
        for (cardId in selectedCards) current = current.addToZone(libraryZone, cardId)
        current = current.copy(otaria = mode.copy(players = mode.players + (
            playerId to player.copy(reserve = player.reserve.filterNot { it in selectedCards })
        )))
        val (shuffled, advanced) = current.nextRandom { shuffle(mixedCards) }
        val rest = shuffled.iterator()
        val reordered = List(library.size + selectedCards.size) { position ->
            byPosition[position] ?: rest.next()
        }
        current = advanced.reorderZone(libraryZone, reordered)
        return ExecutionResult.success(
            current,
            listOf(OtariaPreparedEvent(playerId, selectedCards), LibraryShuffledEvent(playerId, ShuffleCause.OTARIA_PREPARATION)),
        )
    }

    private data class PreservedCard(val id: EntityId, val index: Int, val anchor: LibraryPositionAnchor)
}
