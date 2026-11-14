package com.wingedsheep.engine.otaria

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.PredicateEvaluator
import com.wingedsheep.engine.handlers.effects.ZoneTransitionService
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.TokenComponent
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.effects.Effect

/** Performs the mandatory turn-based choice at the start of each Otaria phase. */
internal class OtariaPhaseActions(
    private val cards: CardRegistry,
    predicateEvaluator: PredicateEvaluator,
    private val executeEffect: (GameState, Effect, EffectContext) -> EffectResult,
    private val zones: ZoneTransitionService,
) {
    private val encounterEntry = OtariaEncounterEntry(zones)
    private val preparation = OtariaPreparation(cards, predicateEvaluator)
    private val context = DecisionContext(sourceName = "Explorer of Otaria")

    fun begin(state: GameState, playerId: EntityId): ExecutionResult {
        val mode = state.otaria ?: return ExecutionResult.error(state, "This is not an Otaria game")
        val turn = mode.turn ?: return ExecutionResult.error(state, "No Otaria action was chosen for this turn")
        if (state.activePlayerId != playerId || turn.playerId != playerId || turn.turnNumber != state.turnNumber) {
            return ExecutionResult.error(state, "The Otaria turn action does not belong to the active player")
        }
        return when (state.step) {
            Step.OTARIA_MOVEMENT -> if (
                (turn.action == OtariaTurnAction.MOVE || turn.encounterAction == OtariaTurnAction.MOVE) &&
                turn.movementAllowed
            ) {
                beginMovement(state, playerId)
            } else ExecutionResult.error(state, "Movement is not allowed this turn")
            Step.OTARIA_PREPARATION -> if (turn.action == OtariaTurnAction.PREPARE) {
                beginPreparation(state, playerId)
            } else ExecutionResult.error(state, "Preparation was not chosen this turn")
            Step.OTARIA_EXPLORATION -> if (turn.action == OtariaTurnAction.EXPLORE && !turn.explorationFinished) {
                beginExploration(state, playerId)
            } else ExecutionResult.error(state, "Exploration is not available this turn")
            else -> ExecutionResult.error(state, "This is not an Otaria action phase")
        }
    }

    private fun beginMovement(state: GameState, playerId: EntityId): ExecutionResult {
        val player = state.otaria?.players?.get(playerId)
            ?: return ExecutionResult.error(state, "The player is not on the Otaria board")
        if (player.position !in OtariaBoard.tiles) return ExecutionResult.error(state, "The player is not on the Otaria map")
        val maximum = OtariaRules.maximumMovementDistance(state, playerId)
        val destinations = OtariaBoard.tiles.keys
            .filter { OtariaBoard.distance(player.position, it) in 1L..maximum }
            .sortedWith(compareBy({ OtariaBoard.distance(player.position, it) }, { it.column }, { it.row }))
        if (destinations.isEmpty()) return ExecutionResult.error(state, "No movement destination is available")
        return state.suspendForDecision(
            question = { id ->
                ChooseOptionDecision(
                    id = id,
                    playerId = playerId,
                    prompt = "Choose a destination",
                    context = context,
                    options = destinations.map { position ->
                        val tile = OtariaBoard.tiles.getValue(position)
                        val description = tile.location?.displayName ?: "${tile.color.displayName} terrain"
                        "Column ${position.column + 1}, row ${position.row + 1}: $description"
                    },
                    optionMetadata = destinations.map { position ->
                        OptionMetadata(id = "otaria-move:${position.column}:${position.row}")
                    },
                )
            },
            answer = OtariaMovementChoiceContinuation(playerId, destinations),
        )
    }

    private fun beginPreparation(state: GameState, playerId: EntityId): ExecutionResult {
        val player = state.otaria?.players?.get(playerId)
            ?: return ExecutionResult.error(state, "The player is not on the Otaria board")
        if (player.reserve.isEmpty()) return preparation.prepare(state, playerId, emptyList())
        val cardInfo = buildMap {
            for (id in player.reserve) {
                val card = state.getEntity(id)?.get<CardComponent>()
                    ?: return ExecutionResult.error(state, "A reserve card is missing: $id")
                put(id, SearchCardInfo(
                    name = card.name,
                    manaCost = card.manaCost.toString(),
                    typeLine = card.typeLine.toString(),
                    imageUri = card.imageUri,
                    colors = card.colors.map { it.name },
                    power = card.baseStats?.basePower,
                ))
            }
        }
        return state.suspendForDecision(
            question = { id ->
                SelectCardsDecision(
                    id = id,
                    playerId = playerId,
                    prompt = "Choose reserve cards to add to your library",
                    context = context,
                    options = player.reserve,
                    minSelections = 0,
                    maxSelections = player.reserve.size,
                    cardInfo = cardInfo,
                    selectedLabel = "Add to library",
                    remainderLabel = "Keep in reserve",
                )
            },
            answer = OtariaPreparationChoiceContinuation(playerId),
        )
    }

    private fun beginExploration(state: GameState, playerId: EntityId): ExecutionResult {
        val mode = state.otaria ?: return ExecutionResult.error(state, "This is not an Otaria game")
        if (mode.pendingExploration != null) return ExecutionResult.error(state, "Another exploration is pending")
        val player = mode.players[playerId]
            ?: return ExecutionResult.error(state, "The player is not on the Otaria board")
        val tile = OtariaBoard.tiles[player.position]
            ?: return ExecutionResult.error(state, "The player is not on the Otaria map")
        if (mode.players.size !in 3..5) return ExecutionResult.error(state, "Otaria requires three to five players")
        val choices = mutableListOf(
            OtariaExplorationChoice(exploreLocation = false, takeLand = true),
            OtariaExplorationChoice(exploreLocation = false, takeLand = false),
        )
        val location = tile.location
        if (location != null) {
            val used = mode.explorationsUsed[location]
                ?: return ExecutionResult.error(state, "The location has no exploration counter")
            val empty = mode.starPile.cards.isEmpty() && mode.starPile.discard.isEmpty()
            val allowed = empty || (
                (player.hasExploredLocationAwayFromStart || location != player.startingLocation) &&
                    used in 0 until location.explorationLimit(mode.players.size)
                )
            if (allowed) {
                choices.add(OtariaExplorationChoice(exploreLocation = true, takeLand = true))
                choices.add(OtariaExplorationChoice(exploreLocation = true, takeLand = false))
            }
        }
        return state.suspendForDecision(
            question = { id ->
                ChooseOptionDecision(
                    id = id,
                    playerId = playerId,
                    prompt = "Choose what to explore",
                    context = context,
                    options = choices.map { choice ->
                        val source = if (choice.exploreLocation) "Starred location" else "${tile.color.displayName} terrain"
                        "$source - ${if (choice.takeLand) "take a basic land" else "no basic land"}"
                    },
                )
            },
            answer = OtariaExplorationChoiceContinuation(playerId, choices),
        )
    }

    fun move(state: GameState, playerId: EntityId, destination: OtariaPosition): ExecutionResult {
        val invalid = validatePhase(state, playerId, Step.OTARIA_MOVEMENT, OtariaTurnAction.MOVE)
        if (invalid != null) return invalid
        return OtariaEncounterLifecycle.retreat(state, playerId, destination, zones)
    }

    fun prepare(state: GameState, playerId: EntityId, selectedCards: List<EntityId>): ExecutionResult {
        val invalid = validatePhase(state, playerId, Step.OTARIA_PREPARATION, OtariaTurnAction.PREPARE)
        if (invalid != null) return invalid
        return preparation.prepare(state, playerId, selectedCards)
    }

    fun explore(state: GameState, playerId: EntityId, choice: OtariaExplorationChoice): ExecutionResult {
        val invalid = validatePhase(state, playerId, Step.OTARIA_EXPLORATION, OtariaTurnAction.EXPLORE)
        if (invalid != null) return invalid
        val started = if (choice.exploreLocation) {
            OtariaRules.startLocationExploration(state, playerId, choice.takeLand, cards)
        } else {
            OtariaRules.startTerrainExploration(state, playerId, choice.takeLand, cards)
        }
        if (started.outcome !is Outcome.Done) return started
        val pending = started.state.otaria?.pendingExploration
        if (pending == null) return finishExploration(started.state, started.events)
        if (!isScripted(pending.card) && pending.card.kind != OtariaCardKind.EVENT) {
            val skipped = OtariaRules.skipUnscriptedCard(started.state, playerId)
            if (skipped.outcome !is Outcome.Done) return skipped.copy(state = state)
            return finishExploration(skipped.state, started.events + skipped.events)
        }
        return when (pending.card.kind) {
            OtariaCardKind.NONCREATURE -> {
                val resolved = OtariaRules.resolveNonCreature(started.state, playerId, cards)
                if (resolved.outcome !is Outcome.Done) return resolved.copy(state = state)
                val added = resolved.events.filterIsInstance<OtariaCardAddedToReserveEvent>().single()
                val definition = cards.requireCard(pending.card.name)
                val revealed = CardsRevealedEvent(
                    revealingPlayerId = pending.managerId,
                    cardIds = listOf(added.cardId),
                    cardNames = listOf(pending.card.name),
                    imageUris = listOf(definition.metadata.imageUri),
                    source = "Explorer of Otaria",
                    cardOwnerIds = listOf(playerId),
                )
                finishExploration(resolved.state, started.events + revealed + resolved.events)
            }
            OtariaCardKind.DISCOVERY -> beginDiscovery(state, started, playerId)
            OtariaCardKind.EVENT -> beginEvent(state, started, pending)
            OtariaCardKind.CREATURE, OtariaCardKind.BOSS -> {
                val entered = encounterEntry.start(started.state, finishExploration = true)
                if (entered.outcome is Outcome.Rejected) {
                    ExecutionResult.rejected(state, entered.outcome.reason)
                } else entered.copy(events = started.events + entered.events)
            }
        }
    }

    private fun isScripted(card: OtariaPileCard): Boolean = when (card.kind) {
        OtariaCardKind.DISCOVERY -> true
        OtariaCardKind.EVENT -> card.eventEffect != null
        OtariaCardKind.NONCREATURE, OtariaCardKind.CREATURE, OtariaCardKind.BOSS -> cards.getCard(card.name) != null
    }

    private fun beginEvent(
        original: GameState,
        started: ExecutionResult,
        pending: OtariaPendingExploration,
    ): ExecutionResult {
        val (sourceId, allocated) = started.state.newEntity()
        val source = CardComponent(
            cardDefinitionId = "otaria-event:${pending.card.name}",
            name = pending.card.name,
            manaCost = ManaCost.ZERO,
            typeLine = TypeLine(cardTypes = emptySet()),
            imageUri = OtariaEventArtwork.forName(pending.card.name),
        )
        var staged = allocated.withEntity(sourceId, ComponentContainer.of(source))
        val reveal = CardsRevealedEvent(
            revealingPlayerId = pending.managerId,
            cardIds = listOf(sourceId),
            cardNames = listOf(pending.card.name),
            imageUris = listOf(source.imageUri),
            source = "Explorer of Otaria",
        )
        if (pending.card.eventEffect == null) {
            val skipped = OtariaRules.skipUnscriptedCard(staged.removeEntity(sourceId), pending.explorerId)
            if (skipped.outcome !is Outcome.Done) return skipped.copy(state = original)
            val revealEvents = if (source.imageUri != null) listOf(reveal) else emptyList()
            return finishExploration(skipped.state, started.events + revealEvents + skipped.events)
        }
        val copyCount = when (pending.card.eventCopyRule) {
            null -> 0
            OtariaEventCopyRule.EACH_SPELL_CAST_THIS_TURN -> started.state.spellsCastThisTurn
        }
        if (copyCount < 0) return ExecutionResult.error(original, "The spell count cannot be negative")
        val copies = mutableListOf<EntityId>()
        repeat(copyCount) {
            val (copyId, next) = staged.newEntity()
            staged = next.withEntity(copyId, ComponentContainer.of(source, TokenComponent))
            copies.add(copyId)
        }
        staged = staged.pushContinuation(FinishOtariaEventContinuation(pending.explorerId, sourceId))
        val copyEvents = if (copies.isEmpty()) emptyList() else listOf(
            OtariaEventCopiesCreatedEvent(pending.explorerId, sourceId, pending.card.name, copies.toList()),
        )
        val result = resolveEventEffects(
            staged, pending.explorerId, pending.managerId, sourceId, copies + sourceId, 0,
        )
        return if (result.outcome is Outcome.Rejected) {
            ExecutionResult.rejected(original, result.outcome.reason)
        } else {
            result.copy(events = started.events + reveal + copyEvents + result.events)
        }
    }

    fun resolveEventEffects(
        state: GameState,
        explorerId: EntityId,
        managerId: EntityId,
        originalSourceId: EntityId,
        sourceIds: List<EntityId>,
        nextIndex: Int,
    ): ExecutionResult {
        val pending = state.otaria?.pendingExploration
            ?: return ExecutionResult.error(state, "No Otaria event is pending")
        if (pending.explorerId != explorerId || pending.managerId != managerId ||
            pending.card.kind != OtariaCardKind.EVENT
        ) return ExecutionResult.error(state, "The pending Otaria event does not match its resolution")
        val effect = pending.card.eventEffect
            ?: return ExecutionResult.error(state, "The pending Otaria event has no effect")
        if (sourceIds.isEmpty() || sourceIds.last() != originalSourceId ||
            sourceIds.distinct().size != sourceIds.size || nextIndex !in sourceIds.indices
        ) return ExecutionResult.error(state, "The Otaria event resolution queue is invalid")
        val original = state.getEntity(originalSourceId)?.get<CardComponent>()
            ?: return ExecutionResult.error(state, "The original Otaria event source is missing")

        var current = state
        val events = mutableListOf<GameEvent>()
        for (index in nextIndex until sourceIds.size) {
            val sourceId = sourceIds[index]
            val source = current.getEntity(sourceId)
                ?: return ExecutionResult.error(current, "An Otaria event source is missing: $sourceId")
            val isCopy = sourceId != originalSourceId
            if (source.get<CardComponent>() != original || source.has<TokenComponent>() != isCopy) {
                return ExecutionResult.error(current, "An Otaria event source does not match its proxy")
            }
            val remainder = if (index + 1 < sourceIds.size) {
                ContinueOtariaEventEffectsContinuation(explorerId, managerId, originalSourceId, sourceIds, index + 1)
            } else null
            val cleanup = if (isCopy) FinishOtariaEventCopyContinuation(explorerId, sourceId) else null
            if (remainder != null) current = current.pushContinuation(remainder)
            if (cleanup != null) current = current.pushContinuation(cleanup)
            val result = executeEffect(
                current, effect,
                EffectContext(
                    sourceId = sourceId,
                    controllerId = explorerId,
                    triggeringEntityId = managerId,
                    triggeringPlayerId = managerId,
                ),
            )
            events.addAll(result.events)
            if (result.outcome !is Outcome.Done) {
                return result.toExecutionResult().copy(events = events)
            }
            current = result.state
            if (cleanup != null) {
                val (frame, popped) = current.popContinuation()
                if (frame != cleanup) return ExecutionResult.error(current, "The Otaria event copy cleanup is missing")
                val finished = finishEventCopy(popped, explorerId, sourceId)
                if (finished.outcome !is Outcome.Done) return finished.copy(events = events + finished.events)
                current = finished.state
                events.addAll(finished.events)
            }
            if (remainder != null) {
                val (frame, popped) = current.popContinuation()
                if (frame != remainder) return ExecutionResult.error(current, "The Otaria event queue is missing")
                current = popped
            }
        }
        return ExecutionResult.success(current, events)
    }

    fun finishEventCopy(state: GameState, explorerId: EntityId, sourceId: EntityId): ExecutionResult {
        val pending = state.otaria?.pendingExploration
            ?: return ExecutionResult.error(state, "No Otaria event is pending")
        val source = state.getEntity(sourceId)
        if (pending.explorerId != explorerId || pending.card.kind != OtariaCardKind.EVENT ||
            source?.has<TokenComponent>() != true ||
            source.get<CardComponent>()?.cardDefinitionId != "otaria-event:${pending.card.name}"
        ) return ExecutionResult.error(state, "The Otaria event copy is missing or does not match")
        return ExecutionResult.success(
            state.removeEntity(sourceId),
            listOf(OtariaEventCopyFinishedEvent(explorerId, sourceId)),
        )
    }

    fun finishEvent(state: GameState, explorerId: EntityId, sourceId: EntityId): ExecutionResult {
        val completed = OtariaRules.finishEvent(state, explorerId, sourceId)
        if (completed.outcome !is Outcome.Done) return completed
        return finishExploration(completed.state, completed.events)
    }

    private fun beginDiscovery(original: GameState, started: ExecutionResult, playerId: EntityId): ExecutionResult {
        val treasure = OtariaRules.resolveDiscoveryTreasure(started.state, playerId, cards)
        if (treasure.outcome !is Outcome.Done) return treasure.copy(state = original)
        val events = started.events + treasure.events
        val mode = requireNotNull(treasure.state.otaria)
        if (mode.locationPile.isEmpty()) {
            val completed = OtariaRules.finishDiscovery(treasure.state, playerId, takeLocation = false)
            if (completed.outcome !is Outcome.Done) return completed.copy(state = original)
            return finishExploration(completed.state, events + completed.events)
        }
        return treasure.state.suspendForDecision(
            question = { id ->
                YesNoDecision(
                    id = id,
                    playerId = playerId,
                    prompt = "Take a location card?",
                    context = context,
                    yesText = "Take a location",
                    noText = "Leave it",
                )
            },
            answer = OtariaDiscoveryChoiceContinuation(playerId),
            events = events,
        )
    }

    fun finishDiscovery(state: GameState, playerId: EntityId, takeLocation: Boolean): ExecutionResult {
        val invalid = validatePhase(state, playerId, Step.OTARIA_EXPLORATION, OtariaTurnAction.EXPLORE)
        if (invalid != null) return invalid
        val completed = OtariaRules.finishDiscovery(state, playerId, takeLocation)
        if (completed.outcome !is Outcome.Done) return completed
        return finishExploration(completed.state, completed.events)
    }

    private fun finishExploration(state: GameState, events: List<GameEvent>): ExecutionResult {
        val mode = state.otaria ?: return ExecutionResult.error(state, "This is not an Otaria game")
        val turn = mode.turn ?: return ExecutionResult.error(state, "No Otaria action was chosen for this turn")
        return ExecutionResult.success(
            state.copy(otaria = mode.copy(turn = turn.copy(explorationFinished = true))),
            events,
        )
    }

    private fun validatePhase(
        state: GameState,
        playerId: EntityId,
        step: Step,
        action: OtariaTurnAction,
    ): ExecutionResult? {
        val turn = state.otaria?.turn
        return if (state.step != step || state.activePlayerId != playerId || turn == null || turn.playerId != playerId ||
            turn.turnNumber != state.turnNumber ||
            (turn.action != action && !(action == OtariaTurnAction.MOVE && turn.encounterAction == action)) ||
            (action == OtariaTurnAction.MOVE && !turn.movementAllowed) ||
            (action == OtariaTurnAction.EXPLORE && turn.explorationFinished)
        ) ExecutionResult.error(state, "The Otaria phase choice is no longer available") else null
    }
}
