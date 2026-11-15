package com.wingedsheep.engine.otaria

import com.wingedsheep.engine.core.ChooseColorDecision
import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.ExecutionResult
import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.core.ManaPoolChangedEvent
import com.wingedsheep.engine.core.OtariaEncounterManaAddedEvent
import com.wingedsheep.engine.core.OtariaEncounterEntryManaChoiceContinuation
import com.wingedsheep.engine.core.OtariaEncounterManaChoiceContinuation
import com.wingedsheep.engine.core.suspendForDecision
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ObjectRef
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.FaceDownComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.state.components.player.RestrictedManaEntry
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.effects.ManaRestriction

internal object OtariaEncounterMana {
    private val manaSymbolPattern = Regex("""\{([^}]+)}""")

    fun isEncounter(state: GameState, actorId: EntityId): Boolean =
        state.otaria?.encounters?.containsKey(actorId) == true

    fun hasUnassignedLegacyMana(state: GameState): Boolean =
        state.otaria?.encounters?.keys?.any { actorId ->
            state.getEntity(actorId)?.get<ManaPoolComponent>()
                ?.manaUnits?.any { it.survivesStepAndTurnEnds } == true
        } == true

    fun hasUnassignedMana(state: GameState): Boolean =
        state.otaria?.encounters?.keys?.any { actorId ->
            val pool = state.getEntity(actorId)?.get<ManaPoolComponent>()
            pool != null && (
                pool.white + pool.blue + pool.black + pool.red + pool.green + pool.colorless > 0 ||
                    pool.restrictedMana.any { it.assignedPermanent == null }
                )
        } == true

    fun memberRef(state: GameState, entityId: EntityId): ObjectRef? {
        val ref = state.objectRef(entityId) ?: return null
        return ref.takeIf { candidate ->
            state.otaria?.encounters?.values?.any { candidate in it.members } == true
        }
    }

    /**
     * A creature produces for itself. Other support permanents produce for a creature only when
     * the encounter explicitly assigned them to that creature's current battlefield object.
     */
    fun sourceOwner(
        state: GameState,
        actorId: EntityId,
        sourceId: EntityId?,
        expectedSource: ObjectRef? = null,
    ): ObjectRef? {
        val encounter = state.otaria?.encounters?.get(actorId) ?: return null
        val source = sourceId?.let(state::objectRef) ?: return null
        if (expectedSource != null && expectedSource != source) return null
        if (source !in encounter.members || sourceId !in state.getBattlefield() ||
            state.projectedState.getController(sourceId) != actorId
        ) return null
        val creature = if (state.projectedState.isCreature(sourceId)) source
        else encounter.manaSources.firstOrNull { it.source == source }?.creature ?: return null
        return creature.takeIf {
            it in encounter.members && state.isCurrentObject(it) &&
                it.entityId in state.getBattlefield() &&
                state.projectedState.isCreature(it.entityId) &&
                state.projectedState.getController(it.entityId) == actorId
        }
    }

    fun paymentOwner(state: GameState, sourceId: EntityId): ObjectRef? {
        val controller = state.projectedState.getController(sourceId) ?: return null
        return sourceOwner(state, controller, sourceId)
    }

    fun needed(state: GameState, actorId: EntityId, creature: ObjectRef): Int {
        return needed(state, actorId, creature, maximumLandCount(state))
    }

    private fun maximumLandCount(state: GameState): Int {
        val projected = state.projectedState
        val landsByController = state.getBattlefield()
            .asSequence()
            .filter { projected.hasType(it, "LAND") }
            .groupingBy { projected.getController(it) }
            .eachCount()
        return state.activePlayers.maxOfOrNull { landsByController[it] ?: 0 } ?: 0
    }

    private fun needed(
        state: GameState,
        actorId: EntityId,
        creature: ObjectRef,
        maximum: Int,
    ): Int {
        val encounter = state.otaria?.encounters?.get(actorId) ?: return 0
        if (creature !in encounter.members || !state.isCurrentObject(creature) ||
            creature.entityId !in state.getBattlefield()
        ) return 0
        val projected = state.projectedState
        if (!projected.isCreature(creature.entityId) || projected.getController(creature.entityId) != actorId) return 0
        val pool = state.getEntity(actorId)?.get<ManaPoolComponent>()
        val lasting = pool?.restrictedMana?.count { it.assignedPermanent == creature && it.remainsUntilSpent } ?: 0
        return (maximum - lasting).coerceAtLeast(0)
    }

    fun chooseNext(state: GameState, actorId: EntityId): ExecutionResult? =
        chooseNext(state, actorId, atEntry = false)

    fun chooseNextAtEntry(state: GameState, actorId: EntityId): ExecutionResult? =
        chooseNext(state, actorId, atEntry = true)

    private fun chooseNext(state: GameState, actorId: EntityId, atEntry: Boolean): ExecutionResult? {
        val encounter = state.otaria?.encounters?.get(actorId) ?: return null
        val maximum = maximumLandCount(state)
        val creatures = encounter.members.sortedBy { it.entityId.value }
        var current = state
        val events = mutableListOf<GameEvent>()

        while (true) {
            val creature = creatures.firstOrNull { needed(current, actorId, it, maximum) > 0 } ?: break
            val remaining = needed(current, actorId, creature, maximum)
            val defaultColor = defaultColor(current, creature)
            if (defaultColor == null) {
                val choice = choose(current, actorId, creature, remaining, atEntry)
                return choice.copy(events = events + choice.events)
            }

            val added = addKnownNeeded(current, actorId, creature, defaultColor)
            if (added.outcome !is com.wingedsheep.engine.core.Outcome.Done) {
                return added.copy(events = events + added.events)
            }
            current = added.state
            events += added.events
        }

        return if (events.isEmpty()) null else ExecutionResult.success(current, events)
    }

    private fun choose(
        state: GameState,
        actorId: EntityId,
        creature: ObjectRef,
        remaining: Int,
        atEntry: Boolean,
    ): ExecutionResult {
        if (remaining <= 0 || needed(state, actorId, creature) < remaining) {
            return ExecutionResult.error(state, "Invalid Otaria encounter mana choice")
        }
        val container = state.getEntity(creature.entityId)
        val name = container?.get<CardComponent>()?.name ?: "Encounter creature"
        val faceDown = container?.has<FaceDownComponent>() == true
        val promptName = if (faceDown) "face-down creature" else name
        return state.suspendForDecision(
            question = { id ->
                ChooseColorDecision(
                    id = id,
                    playerId = actorId,
                    prompt = "Choose a color for $promptName's encounter mana ($remaining remaining)",
                    context = DecisionContext(sourceId = creature.entityId, sourceName = name, sourceIsPrivate = faceDown),
                )
            },
            answer = if (atEntry) OtariaEncounterEntryManaChoiceContinuation(actorId, creature, remaining)
            else OtariaEncounterManaChoiceContinuation(actorId, creature, remaining),
        )
    }

    private fun defaultColor(state: GameState, creature: ObjectRef): Color? {
        val container = state.getEntity(creature.entityId) ?: return null
        if (container.has<FaceDownComponent>()) return null
        val card = container.get<CardComponent>() ?: return null
        val identity = card.colorIdentity
        if (identity?.size == 1) return identity.single()
        val counts = mutableMapOf<Color, Int>()
        fun count(colors: Iterable<Color>) {
            colors.forEach { color ->
                if (identity == null || color in identity) {
                    counts[color] = (counts[color] ?: 0) + 1
                }
            }
        }

        count(card.colors)
        card.manaCost.symbols.forEach { count(it.colors) }
        for (symbol in manaSymbolPattern.findAll(card.oracleText)) {
            count(symbol.groupValues[1].mapNotNull { Color.fromSymbol(it) })
        }
        identity?.forEach { counts.putIfAbsent(it, 0) }

        if (counts.size == 1) return counts.keys.single()
        val highestCount = counts.values.maxOrNull() ?: return null
        return counts.entries.singleOrNull { it.value == highestCount }?.key
    }

    fun add(state: GameState, actorId: EntityId, creature: ObjectRef, color: Color): ExecutionResult {
        if (actorId !in state.otaria?.encounters.orEmpty()) {
            return ExecutionResult.error(state, "The Otaria encounter is no longer active")
        }
        if (needed(state, actorId, creature) <= 0) {
            return ExecutionResult.error(state, "The encounter has reached its rule-mana limit")
        }
        return addKnownNeeded(state, actorId, creature, color)
    }

    private fun addKnownNeeded(
        state: GameState,
        actorId: EntityId,
        creature: ObjectRef,
        color: Color,
    ): ExecutionResult {
        val pool = state.getEntity(actorId)?.get<ManaPoolComponent>()
            ?: return ExecutionResult.error(state, "The Otaria encounter has no mana pool")
        val unit = RestrictedManaEntry(
            color, ManaRestriction.AnySpend, assignedPermanent = creature, remainsUntilSpent = true,
        )
        val updated = pool.copy(restrictedMana = pool.restrictedMana + unit)
        return ExecutionResult.success(
            state.updateEntity(actorId) { it.with(updated) },
            listOf(OtariaEncounterManaAddedEvent(actorId, creature, color), ManaPoolChangedEvent(actorId)),
        )
    }

    fun removeForDeparture(state: GameState, creature: ObjectRef): Pair<GameState, List<GameEvent>> {
        var current = state
        val events = mutableListOf<GameEvent>()
        for ((actorId, encounter) in state.otaria?.encounters.orEmpty()) {
            if (creature !in encounter.members) continue
            val pool = current.getEntity(actorId)?.get<ManaPoolComponent>() ?: continue
            val remaining = pool.restrictedMana.filterNot { it.assignedPermanent == creature }
            if (remaining.size == pool.restrictedMana.size) continue
            current = current.updateEntity(actorId) { it.with(pool.copy(restrictedMana = remaining)) }
            events.add(ManaPoolChangedEvent(actorId))
        }
        return current to events
    }
}
