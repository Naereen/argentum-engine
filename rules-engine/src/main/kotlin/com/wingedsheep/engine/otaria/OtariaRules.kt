package com.wingedsheep.engine.otaria

import com.wingedsheep.engine.core.CardEntityFactory
import com.wingedsheep.engine.core.CardsRevealedEvent
import com.wingedsheep.engine.core.ExecutionResult
import com.wingedsheep.engine.core.GameEndReason
import com.wingedsheep.engine.core.GameEndedEvent
import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.core.OtariaCardAddedToReserveEvent
import com.wingedsheep.engine.core.OtariaDiscoveredTreasure
import com.wingedsheep.engine.core.OtariaDiscoveryFinishedEvent
import com.wingedsheep.engine.core.OtariaDiscoveryTreasureResolvedEvent
import com.wingedsheep.engine.core.OtariaEventFinishedEvent
import com.wingedsheep.engine.core.OtariaCardSkippedEvent
import com.wingedsheep.engine.core.OtariaExplorationEmptyEvent
import com.wingedsheep.engine.core.OtariaExplorationPileShuffledEvent
import com.wingedsheep.engine.core.OtariaExplorationStartedEvent
import com.wingedsheep.engine.core.OtariaPlayerMovedEvent
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.OwnerComponent
import com.wingedsheep.engine.state.components.identity.TokenComponent
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.EntityId

/**
 * Internal transitions for the variant. A host must still provide the exploration phase,
 * encounter resolution and a player-facing action before offering these transitions to players.
 */
internal object OtariaRules {
    /**
     * Move when no encounter opposes the player. The caller must enforce the movement phase and
     * determine whether a retreat is opposed; resolving an encounter is not implemented here.
     */
    fun moveUnopposed(state: GameState, playerId: EntityId, destination: OtariaPosition): ExecutionResult {
        val mode = state.otaria ?: return ExecutionResult.error(state, "This is not an Otaria game")
        if (state.gameOver) return ExecutionResult.error(state, "The game is already over")
        if (state.activePlayerId != playerId || playerId !in state.activePlayers) {
            return ExecutionResult.error(state, "Only the active player can move")
        }
        if (state.pendingDecision != null || mode.pendingExploration != null) {
            return ExecutionResult.error(state, "Another decision or exploration is pending")
        }
        val player = mode.players[playerId]
            ?: return ExecutionResult.error(state, "The player is not on the Otaria board")
        if (player.position !in OtariaBoard.tiles || destination !in OtariaBoard.tiles) {
            return ExecutionResult.error(state, "Both positions must be on the Otaria map")
        }
        val maximum = maximumMovementDistance(state, playerId)
        if (OtariaBoard.distance(player.position, destination) !in 1L..maximum) {
            return ExecutionResult.error(state, "The destination is outside the player's movement range")
        }
        val nextMode = mode.copy(players = mode.players + (playerId to player.copy(position = destination)))
        return ExecutionResult.success(
            state.copy(otaria = nextMode),
            listOf(OtariaPlayerMovedEvent(playerId, player.position, destination)),
        )
    }

    fun maximumMovementDistance(state: GameState, playerId: EntityId): Long =
        if (state.projectedState.getBattlefieldControlledBy(playerId).size >= 10) 1L else 2L

    fun startLocationExploration(
        state: GameState,
        explorerId: EntityId,
        takeLand: Boolean,
        cardRegistry: CardRegistry,
    ): ExecutionResult = startExploration(
        state, explorerId, exploreLocation = true, takeLand = takeLand, cardRegistry = cardRegistry,
    )

    fun startTerrainExploration(
        state: GameState,
        explorerId: EntityId,
        takeLand: Boolean,
        cardRegistry: CardRegistry,
    ): ExecutionResult = startExploration(
        state, explorerId, exploreLocation = false, takeLand = takeLand, cardRegistry = cardRegistry,
    )

    private fun startExploration(
        state: GameState,
        explorerId: EntityId,
        exploreLocation: Boolean,
        takeLand: Boolean,
        cardRegistry: CardRegistry,
    ): ExecutionResult {
        val mode = state.otaria ?: return ExecutionResult.error(state, "This is not an Otaria game")
        if (state.gameOver) return ExecutionResult.error(state, "The game is already over")
        if (state.activePlayerId != explorerId || explorerId !in state.activePlayers) {
            return ExecutionResult.error(state, "Only the active player can explore")
        }
        if (state.pendingDecision != null || mode.pendingExploration != null) {
            return ExecutionResult.error(state, "Another decision or exploration is pending")
        }
        val player = mode.players[explorerId]
            ?: return ExecutionResult.error(state, "The player is not on the Otaria board")
        val tile = OtariaBoard.tiles[player.position]
            ?: return ExecutionResult.error(state, "The player is not on the Otaria map")
        val source = if (exploreLocation) {
            val location = tile.location
                ?: return ExecutionResult.error(state, "The player is not on a starred location")
            OtariaExplorationSource.Star(location)
        } else {
            OtariaExplorationSource.Terrain(tile.color)
        }
        val originalPile = when (source) {
            is OtariaExplorationSource.Star -> mode.starPile
            is OtariaExplorationSource.Terrain -> mode.colorPiles[source.color]
                ?: return ExecutionResult.error(state, "The ${source.color} exploration pile does not exist")
        }
        val pileEmpty = originalPile.cards.isEmpty() && originalPile.discard.isEmpty()
        if (!pileEmpty && source is OtariaExplorationSource.Star) {
            val location = source.location
            if (!player.hasExploredLocationAwayFromStart && location == player.startingLocation) {
                return ExecutionResult.error(state, "The first location exploration cannot be at the starting location")
            }
            val used = mode.explorationsUsed[location]
                ?: return ExecutionResult.error(state, "The location has no exploration counter")
            if (used < 0 || used >= location.explorationLimit(mode.players.size)) {
                return ExecutionResult.error(state, "The location has no explorations remaining")
            }
        }
        val managerId = if (!pileEmpty) {
            val seats = state.activePlayers
            if (seats.size < 2) return ExecutionResult.error(state, "No opponent can manage the exploration")
            seats[(seats.indexOf(explorerId) + 1) % seats.size]
        } else null
        val landDefinition = if (takeLand) {
            val subtype = basicLandSubtype(tile.color)
            val landName = subtype.value
            val definition = cardRegistry.getCard(landName)
                ?: return ExecutionResult.error(state, "Basic land card not found in registry: $landName")
            if (!definition.typeLine.isBasicLand || !definition.typeLine.hasSubtype(subtype) || definition.name != landName) {
                return ExecutionResult.error(state, "The registry entry for $landName is not the matching basic land")
            }
            definition
        } else null

        var current = state
        val events = mutableListOf<GameEvent>()
        var updatedPlayer = player
        if (landDefinition != null) {
            val added = addToReserve(current, explorerId, updatedPlayer, landDefinition)
            current = added.state
            updatedPlayer = added.player
            events.add(OtariaCardAddedToReserveEvent(explorerId, added.cardId, landDefinition.name))
        }
        if (pileEmpty) {
            events.add(OtariaExplorationEmptyEvent(explorerId, source))
            return ExecutionResult.success(
                current.copy(otaria = mode.copy(players = mode.players + (explorerId to updatedPlayer))),
                events,
            )
        }
        val pile = if (originalPile.cards.isEmpty()) {
            val (shuffled, advanced) = current.nextRandom { shuffle(originalPile.discard) }
            current = advanced
            events.add(OtariaExplorationPileShuffledEvent(source, shuffled.size))
            OtariaExplorationPile(shuffled)
        } else {
            originalPile
        }
        val card = pile.cards.first()
        if (card.kind == OtariaCardKind.CREATURE || card.kind == OtariaCardKind.BOSS) {
            // A card missing from the registry is drawn anyway: the explorer discards it as unscripted.
            val definition = cardRegistry.getCard(card.name)
            if (definition != null && !definition.typeLine.isCreature) {
                return ExecutionResult.error(state, "The encounter card is not a creature: ${card.name}")
            }
        }
        val remaining = pile.copy(cards = pile.cards.drop(1))
        val manager = requireNotNull(managerId)
        val pending = OtariaPendingExploration(explorerId, manager, source, card)
        val location = (source as? OtariaExplorationSource.Star)?.location
        if (location != null) {
            updatedPlayer = updatedPlayer.copy(hasExploredLocationAwayFromStart = true)
        }
        val nextMode = mode.copy(
            players = mode.players + (explorerId to updatedPlayer),
            explorationsUsed = if (location == null) mode.explorationsUsed else {
                mode.explorationsUsed + (location to mode.explorationsUsed.getValue(location) + 1)
            },
            starPile = if (source is OtariaExplorationSource.Star) remaining else mode.starPile,
            colorPiles = if (source is OtariaExplorationSource.Terrain) {
                mode.colorPiles + (source.color to remaining)
            } else mode.colorPiles,
            pendingExploration = pending,
        )
        events.add(OtariaExplorationStartedEvent(explorerId, manager, source, card))
        return ExecutionResult.success(
            current.copy(otaria = nextMode),
            events,
        )
    }

    fun resolveNonCreature(state: GameState, explorerId: EntityId, cardRegistry: CardRegistry): ExecutionResult {
        val mode = state.otaria ?: return ExecutionResult.error(state, "This is not an Otaria game")
        if (state.gameOver) return ExecutionResult.error(state, "The game is already over")
        if (state.activePlayerId != explorerId || explorerId !in state.activePlayers) {
            return ExecutionResult.error(state, "Only the active explorer can resolve the card")
        }
        if (state.pendingDecision != null) return ExecutionResult.error(state, "Another decision is pending")
        val pending = mode.pendingExploration
            ?: return ExecutionResult.error(state, "No exploration is pending")
        if (pending.explorerId != explorerId || pending.source !is OtariaExplorationSource.Terrain ||
            pending.card.kind != OtariaCardKind.NONCREATURE || pending.stage != OtariaExplorationStage.DRAWN
        ) {
            return ExecutionResult.error(state, "No noncreature card is pending for this explorer")
        }
        val player = mode.players[explorerId]
            ?: return ExecutionResult.error(state, "The player is not on the Otaria board")
        val definition = cardRegistry.getCard(pending.card.name)
            ?: return ExecutionResult.error(state, "Exploration card not found in registry: ${pending.card.name}")
        if (definition.typeLine.isCreature || definition.typeLine.isLand) {
            return ExecutionResult.error(state, "The exploration card is not a noncreature spell: ${pending.card.name}")
        }
        val added = addToReserve(state, explorerId, player, definition)
        val nextMode = mode.copy(
            players = mode.players + (explorerId to added.player),
            pendingExploration = null,
        )
        return ExecutionResult.success(
            added.state.copy(otaria = nextMode),
            listOf(OtariaCardAddedToReserveEvent(explorerId, added.cardId, definition.name)),
        )
    }

    /**
     * Resolve the treasure before asking whether to draw a location, so the player can make that
     * choice after seeing the treasure. Missing card definitions reject without consuming it.
     */
    fun resolveDiscoveryTreasure(state: GameState, explorerId: EntityId, cardRegistry: CardRegistry): ExecutionResult {
        val mode = state.otaria ?: return ExecutionResult.error(state, "This is not an Otaria game")
        if (state.gameOver) return ExecutionResult.error(state, "The game is already over")
        if (state.activePlayerId != explorerId || explorerId !in state.activePlayers) {
            return ExecutionResult.error(state, "Only the active explorer can resolve the discovery")
        }
        if (state.pendingDecision != null) return ExecutionResult.error(state, "Another decision is pending")
        val pending = mode.pendingExploration
            ?: return ExecutionResult.error(state, "No exploration is pending")
        if (pending.explorerId != explorerId || pending.card.kind != OtariaCardKind.DISCOVERY ||
            pending.stage != OtariaExplorationStage.DRAWN
        ) {
            return ExecutionResult.error(state, "No treasure draw is pending for this explorer")
        }
        val player = mode.players[explorerId]
            ?: return ExecutionResult.error(state, "The player is not on the Otaria board")
        val nextTreasure = mode.treasurePile.firstOrNull()
        var current = state
        var updatedPlayer = player
        val treasure = when (nextTreasure) {
            is OtariaTreasure.Card -> {
                val definition = cardRegistry.getCard(nextTreasure.name)
                    ?: return ExecutionResult.error(state, "Treasure card not found in registry: ${nextTreasure.name}")
                val added = addToReserve(current, explorerId, player, definition)
                current = added.state
                updatedPlayer = added.player
                OtariaDiscoveredTreasure.Card(added.cardId, definition.name)
            }
            is OtariaTreasure.Token -> {
                val tokens = player.treasureTokens
                updatedPlayer = player.copy(treasureTokens = tokens + (
                    nextTreasure.kind to (tokens.getOrDefault(nextTreasure.kind, 0) + 1)
                ))
                OtariaDiscoveredTreasure.Token(nextTreasure.kind)
            }
            null -> null
        }
        val nextMode = mode.copy(
            players = mode.players + (explorerId to updatedPlayer),
            treasurePile = if (treasure == null) mode.treasurePile else mode.treasurePile.drop(1),
            pendingExploration = pending.copy(stage = OtariaExplorationStage.CHOOSING_LOCATION),
        )
        return ExecutionResult.success(
            current.copy(otaria = nextMode),
            listOf(OtariaDiscoveryTreasureResolvedEvent(explorerId, treasure)),
        )
    }

    fun finishDiscovery(state: GameState, explorerId: EntityId, takeLocation: Boolean): ExecutionResult {
        val mode = state.otaria ?: return ExecutionResult.error(state, "This is not an Otaria game")
        if (state.gameOver) return ExecutionResult.error(state, "The game is already over")
        if (state.activePlayerId != explorerId || explorerId !in state.activePlayers) {
            return ExecutionResult.error(state, "Only the active explorer can finish the discovery")
        }
        if (state.pendingDecision != null) return ExecutionResult.error(state, "Another decision is pending")
        val pending = mode.pendingExploration
            ?: return ExecutionResult.error(state, "No exploration is pending")
        if (pending.explorerId != explorerId || pending.card.kind != OtariaCardKind.DISCOVERY ||
            pending.stage != OtariaExplorationStage.CHOOSING_LOCATION
        ) {
            return ExecutionResult.error(state, "No location choice is pending for this explorer")
        }
        val player = mode.players[explorerId]
            ?: return ExecutionResult.error(state, "The player is not on the Otaria board")
        val location = if (takeLocation) {
            mode.locationPile.firstOrNull() ?: return ExecutionResult.error(state, "The location pile is empty")
        } else null
        val withDiscard = when (val source = pending.source) {
            is OtariaExplorationSource.Star -> mode.copy(
                starPile = mode.starPile.copy(discard = mode.starPile.discard + pending.card),
            )
            is OtariaExplorationSource.Terrain -> {
                val pile = mode.colorPiles[source.color]
                    ?: return ExecutionResult.error(state, "The ${source.color} exploration pile does not exist")
                mode.copy(colorPiles = mode.colorPiles + (
                    source.color to pile.copy(discard = pile.discard + pending.card)
                ))
            }
        }
        val nextMode = withDiscard.copy(
            players = if (location == null) mode.players else mode.players + (
                explorerId to player.copy(locationCards = player.locationCards + location)
            ),
            locationPile = if (location == null) mode.locationPile else mode.locationPile.drop(1),
            pendingExploration = null,
        )
        return ExecutionResult.success(
            state.copy(otaria = nextMode),
            listOf(OtariaDiscoveryFinishedEvent(explorerId, location)),
        )
    }

    fun finishEvent(state: GameState, explorerId: EntityId, sourceId: EntityId): ExecutionResult {
        val mode = state.otaria ?: return ExecutionResult.error(state, "This is not an Otaria game")
        val pending = mode.pendingExploration
            ?: return ExecutionResult.error(state, "No exploration is pending")
        if (pending.explorerId != explorerId || pending.card.kind != OtariaCardKind.EVENT ||
            pending.stage != OtariaExplorationStage.DRAWN ||
            state.getEntity(sourceId)?.get<CardComponent>()?.name != pending.card.name
        ) {
            return ExecutionResult.error(state, "No matching Otaria event is being resolved")
        }
        val withDiscard = when (val source = pending.source) {
            is OtariaExplorationSource.Star -> mode.copy(
                starPile = mode.starPile.copy(discard = mode.starPile.discard + pending.card),
            )
            is OtariaExplorationSource.Terrain -> {
                val pile = mode.colorPiles[source.color]
                    ?: return ExecutionResult.error(state, "The ${source.color} exploration pile does not exist")
                mode.copy(colorPiles = mode.colorPiles + (
                    source.color to pile.copy(discard = pile.discard + pending.card)
                ))
            }
        }
        return ExecutionResult.success(
            state.removeEntity(sourceId).copy(otaria = withDiscard.copy(pendingExploration = null)),
            listOf(OtariaEventFinishedEvent(explorerId, pending.source, pending.card.name)),
        )
    }

    /** Discards a drawn card that has no script in this build; location counters are left as they are. */
    fun skipUnscriptedCard(state: GameState, explorerId: EntityId): ExecutionResult {
        val mode = state.otaria ?: return ExecutionResult.error(state, "This is not an Otaria game")
        val pending = mode.pendingExploration
            ?: return ExecutionResult.error(state, "No exploration is pending")
        if (pending.explorerId != explorerId || pending.stage != OtariaExplorationStage.DRAWN) {
            return ExecutionResult.error(state, "No drawn exploration card can be skipped")
        }
        val withDiscard = when (val source = pending.source) {
            is OtariaExplorationSource.Star -> mode.copy(
                starPile = mode.starPile.copy(discard = mode.starPile.discard + pending.card),
            )
            is OtariaExplorationSource.Terrain -> {
                val pile = mode.colorPiles[source.color]
                    ?: return ExecutionResult.error(state, "The ${source.color} exploration pile does not exist")
                mode.copy(colorPiles = mode.colorPiles + (
                    source.color to pile.copy(discard = pile.discard + pending.card)
                ))
            }
        }
        return ExecutionResult.success(
            state.copy(otaria = withDiscard.copy(pendingExploration = null)),
            listOf(OtariaCardSkippedEvent(explorerId, pending.source, pending.card.name)),
        )
    }

    private data class ReserveAddition(
        val state: GameState,
        val player: OtariaPlayerState,
        val cardId: EntityId,
    )

    private fun addToReserve(
        state: GameState,
        playerId: EntityId,
        player: OtariaPlayerState,
        definition: CardDefinition,
    ): ReserveAddition {
        val (cardId, allocated) = state.newEntity()
        return ReserveAddition(
            allocated.withEntity(cardId, CardEntityFactory.create(definition, playerId)),
            player.copy(reserve = player.reserve + cardId),
            cardId,
        )
    }

    private fun basicLandSubtype(color: Color): Subtype = when (color) {
        Color.WHITE -> Subtype.PLAINS
        Color.BLUE -> Subtype.ISLAND
        Color.BLACK -> Subtype.SWAMP
        Color.RED -> Subtype.MOUNTAIN
        Color.GREEN -> Subtype.FOREST
    }

    /**
     * Resolve the optional location-card victory directly, not as a spell or ability on the stack.
     */
    fun claimMirariVictory(state: GameState, playerId: EntityId, mirariId: EntityId): ExecutionResult {
        val error = validateMirariVictory(state, playerId, mirariId)
        if (error != null) return ExecutionResult.error(state, error)
        val card = requireNotNull(state.getEntity(mirariId)?.get<CardComponent>())
        val mode = requireNotNull(state.otaria)
        return ExecutionResult.success(
            state.copy(gameOver = true, winnerId = playerId, otaria = mode.copy(mirariVictoryClaimed = true)),
            listOf(
                CardsRevealedEvent(
                    revealingPlayerId = playerId,
                    cardIds = listOf(mirariId),
                    cardNames = listOf(OtariaSetup.MIRARI),
                    imageUris = listOf(card.imageUri),
                    source = "Explorer of Otaria",
                ),
                GameEndedEvent(playerId, GameEndReason.ALTERNATIVE_WIN),
            ),
        )
    }

    fun validateMirariVictory(state: GameState, playerId: EntityId, mirariId: EntityId): String? {
        val mode = state.otaria ?: return "This is not an Otaria game"
        if (state.gameOver) return "The game is already over"
        if (playerId !in state.activePlayers) return "The player is not in the game"
        val player = mode.players[playerId]
            ?: return "The player is not on the Otaria board"
        val location = OtariaBoard.tiles[player.position]?.location
            ?: return "The player is not on a starred location"
        if (location !in player.locationCards) {
            return "The player does not have the matching location card"
        }
        val container = state.getEntity(mirariId)
            ?: return "The Mirari card does not exist"
        val card = container.get<CardComponent>()
            ?: return "The chosen object is not a card"
        if (container.has<TokenComponent>()) return "The chosen object is a token"
        val inReserve = mirariId in player.reserve &&
            container.get<OwnerComponent>()?.playerId == playerId &&
            card.name == OtariaSetup.MIRARI
        val onBattlefield = if (mirariId in state.getBattlefield()) {
            val projected = state.projectedState
            projected.getController(mirariId) == playerId &&
                !projected.isFaceDown(mirariId) &&
                (projected.getName(mirariId) ?: card.name) == OtariaSetup.MIRARI
        } else false
        if (!inReserve && !onBattlefield) {
            return "The player neither owns Mirari in reserve nor controls it on the battlefield"
        }
        return null
    }

    fun mirariVictoryCandidates(state: GameState, playerId: EntityId): List<EntityId> {
        val player = state.otaria?.players?.get(playerId) ?: return emptyList()
        val location = OtariaBoard.tiles[player.position]?.location ?: return emptyList()
        if (location !in player.locationCards) return emptyList()
        return (player.reserve.asSequence() + state.getBattlefield().asSequence())
            .distinct()
            .filter { validateMirariVictory(state, playerId, it) == null }
            .toList()
    }
}
