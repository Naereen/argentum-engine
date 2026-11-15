package com.wingedsheep.engine.otaria

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.handlers.effects.ZoneTransitionService
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.battlefield.PhasedOutComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId

/** Variant outcomes are applied after real zone transitions, retaining their frozen leaves information. */
internal object OtariaEncounterLifecycle {
    fun departures(
        state: GameState,
        events: List<GameEvent>,
        cards: CardRegistry,
        responsiblePlayer: EntityId? = null,
    ): ExecutionResult {
        if (state.otaria?.encounters.isNullOrEmpty()) return ExecutionResult.success(state, events)
        var current = state
        val emitted = events.toMutableList()
        for (event in events.filterIsInstance<ZoneChangeEvent>()) {
            if (event.fromZone != Zone.BATTLEFIELD || event.toZone == Zone.BATTLEFIELD) continue
            val mode = current.otaria ?: break
            val (actor, encounter) = mode.encounters.entries.firstOrNull {
                event.oldObject != null && event.oldObject in it.value.members
            } ?: continue
            val members = encounter.members.filterNot { it == event.oldObject }.toSet()
            current = current.copy(otaria = mode.copy(
                encounters = mode.encounters + (actor to encounter.copy(
                    members = members,
                    pileCard = encounter.pileCard.takeUnless { event.entityId == encounter.primaryId },
                    manaSources = encounter.manaSources.filter {
                        it.source != event.oldObject && it.creature != event.oldObject
                    },
                )),
            ))
            if (event.toZone == Zone.GRAVEYARD && event.lastKnown?.wasToken != true &&
                event.lastKnown?.typeLine?.isCreature == true
            ) {
                val captor = responsiblePlayer?.takeIf { it in current.activePlayers }
                    ?: event.lastKnown.damageSources.lastOrNull {
                        it.sourceControllerId in current.activePlayers
                    }?.sourceControllerId
                    ?: encounter.explorerId.takeIf { it in current.activePlayers }
                if (captor != null) {
                    val card = current.getEntity(event.entityId)?.get<CardComponent>()
                    checkNotNull(card) { "A defeated Otaria creature has no card: ${event.entityId}" }
                    val definition = cards.requireCard(card.cardDefinitionId)
                    val (reserveId, allocated) = current.newEntity()
                    val staged = allocated.removeEntity(event.entityId)
                        .withEntity(reserveId, CardEntityFactory.create(definition, captor))
                    val nextMode = requireNotNull(staged.otaria)
                    val player = nextMode.players.getValue(captor)
                    current = staged.copy(otaria = nextMode.copy(
                        players = nextMode.players + (captor to player.copy(reserve = player.reserve + reserveId)),
                    ))
                    emitted.add(OtariaCardAddedToReserveEvent(captor, reserveId, definition.name))
                    emitted.add(OtariaCreatureCapturedEvent(actor, captor, reserveId))
                }
            } else if (event.entityId == encounter.primaryId &&
                event.toZone in setOf(Zone.HAND, Zone.LIBRARY)
            ) {
                val returned = returnToPile(current, encounter)
                current = returned.state.removeEntity(event.entityId)
                emitted.addAll(returned.events)
            }
        }
        return ExecutionResult.success(current, emitted)
    }

    fun retreat(
        state: GameState,
        playerId: EntityId,
        destination: OtariaPosition,
        zones: ZoneTransitionService,
    ): ExecutionResult {
        val mode = state.otaria ?: return ExecutionResult.error(state, "This is not an Otaria game")
        val player = mode.players[playerId] ?: return ExecutionResult.error(state, "The explorer is missing")
        val present = mode.encounters.filter { (_, encounter) ->
            playerId in encounter.participants && encounter.position == player.position
        }
        val opposed = present.filterValues { encounter ->
            encounter.members.any {
                state.objectRef(it.entityId) == it && state.projectedState.isCreature(it.entityId) &&
                    it.entityId in state.getBattlefield() &&
                    state.getEntity(it.entityId)?.has<PhasedOutComponent>() != true
            }
        }
        val free = mode.turn?.encounterAction == OtariaTurnAction.MOVE &&
            mode.turn.encounterInitiative == OtariaInitiativeSide.EXPLORER
        val (plan, rng) = OtariaContestPlanner.retreat(state.rng, if (free) emptyList() else opposed.keys.toList())
        val rolled = state.copy(rng = rng)
        val event = OtariaRetreatDeterminedEvent(playerId, plan)
        if (!plan.succeeds) {
            val turn = requireNotNull(mode.turn)
            return ExecutionResult.success(rolled.copy(otaria = mode.copy(
                turn = turn.copy(movementAllowed = false),
            )), listOf(event))
        }
        val moved = OtariaRules.moveUnopposed(rolled, playerId, destination)
        if (moved.outcome !is Outcome.Done) return moved
        var current = moved.state
        val emitted = mutableListOf<GameEvent>(event)
        emitted.addAll(moved.events)
        for ((actor, original) in present) {
            val remaining = original.participants.filter { participant ->
                participant != playerId && participant in current.activePlayers &&
                    current.otaria?.players?.get(participant)?.position == original.position
            }.toSet()
            val nextMode = requireNotNull(current.otaria)
            current = current.copy(otaria = nextMode.copy(
                encounters = nextMode.encounters + (actor to original.copy(participants = remaining)),
            ))
            if (remaining.isEmpty()) {
                val returned = returnToPile(current, original)
                current = returned.state
                emitted.addAll(returned.events)
                val cleared = clear(current, actor, zones)
                current = cleared.state
                emitted.addAll(cleared.events)
            }
        }
        return ExecutionResult.success(current, emitted)
    }

    /** A side with no active opponents returns its drawn card; its final turn/stack can still finish. */
    fun finishEmpty(state: GameState, zones: ZoneTransitionService): ExecutionResult {
        val mode = state.otaria ?: return ExecutionResult.success(state)
        if (state.pendingDecision != null || state.stack.isNotEmpty() || state.pendingTriggers.isNotEmpty() ||
            state.triggersBeingPlaced.isNotEmpty() || mode.pendingExploration != null
        ) return ExecutionResult.success(state)
        var current = state
        val events = mutableListOf<GameEvent>()
        for ((actor, _) in mode.encounters) {
            val encounter = current.otaria?.encounters?.get(actor) ?: continue
            val participants = encounter.participants.filterTo(mutableSetOf()) { it in current.activePlayers }
            if (participants != encounter.participants) {
                val currentMode = requireNotNull(current.otaria)
                current = current.copy(otaria = currentMode.copy(
                    encounters = currentMode.encounters + (actor to encounter.copy(participants = participants)),
                ))
            }
            if (participants.isEmpty()) {
                val returned = returnToPile(current, encounter)
                current = returned.state
                events.addAll(returned.events)
                val cleared = clear(current, actor, zones, preserveActiveActor = actor == current.activePlayerId)
                current = cleared.state
                events.addAll(cleared.events)
                continue
            }
            if (actor == current.activePlayerId) continue
            if (encounter.members.any {
                    current.objectRef(it.entityId) == it && it.entityId in current.getBattlefield()
                }
            ) continue
            val cleared = clear(current, actor, zones)
            current = cleared.state
            events.addAll(cleared.events)
        }
        return ExecutionResult.success(current, events)
    }

    private fun returnToPile(state: GameState, encounter: OtariaEncounter): ExecutionResult {
        val card = encounter.pileCard ?: return ExecutionResult.success(state)
        val mode = requireNotNull(state.otaria)
        val pile = when (val source = encounter.source) {
            is OtariaExplorationSource.Star -> mode.starPile
            is OtariaExplorationSource.Terrain -> mode.colorPiles.getValue(source.color)
        }
        val (shuffled, rng) = state.rng.shuffle(pile.cards + card)
        val updated = pile.copy(cards = shuffled)
        val next = when (val source = encounter.source) {
            is OtariaExplorationSource.Star -> mode.copy(starPile = updated)
            is OtariaExplorationSource.Terrain -> mode.copy(colorPiles = mode.colorPiles + (source.color to updated))
        }
        return ExecutionResult.success(state.copy(rng = rng, otaria = next), listOf(
            OtariaEncounterReturnedEvent(encounter.explorerId, encounter.source),
            OtariaExplorationPileShuffledEvent(encounter.source, shuffled.size),
        ))
    }

    private fun clear(
        state: GameState,
        actorId: EntityId,
        zones: ZoneTransitionService,
        preserveActiveActor: Boolean = false,
    ): ExecutionResult {
        var current = state
        val events = mutableListOf<GameEvent>()
        for (id in current.getBattlefield().filter { current.logicalZone(it)?.ownerId == actorId }) {
            val moved = zones.moveToZone(current, id, Zone.EXILE)
            current = moved.state
            events.addAll(moved.events)
        }
        val owned = current.zones.filterKeys { it.ownerId == actorId }.values.flatten().distinct()
        for (id in owned) current = current.removeEntity(id)
        val mode = requireNotNull(current.otaria)
        if (preserveActiveActor) {
            val encounter = mode.encounters.getValue(actorId)
            // Keep the active actor in turn order, but remove its board and drawn card now.
            return ExecutionResult.success(current.copy(otaria = mode.copy(
                encounters = mode.encounters + (actorId to encounter.copy(
                    members = emptySet(),
                    participants = emptySet(),
                    manaSources = emptyList(),
                    pileCard = null,
                )),
            )), events)
        }
        current = current.removeEntity(actorId).copy(
            otaria = mode.copy(encounters = mode.encounters - actorId),
            priorityPassedBy = current.priorityPassedBy - actorId,
        )
        if (current.priorityPlayerId == actorId) current = current.withPriority(current.activePlayerId)
        events.add(OtariaEncounterEndedEvent(actorId))
        return ExecutionResult.success(current, events)
    }
}
