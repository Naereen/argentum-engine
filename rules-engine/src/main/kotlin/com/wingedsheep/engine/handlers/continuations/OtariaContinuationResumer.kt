package com.wingedsheep.engine.handlers.continuations

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.handlers.effects.EffectEntryChoices
import com.wingedsheep.engine.handlers.effects.copy.EffectCopyEntry
import com.wingedsheep.engine.otaria.OtariaEncounterEntry
import com.wingedsheep.engine.otaria.OtariaTurn
import com.wingedsheep.engine.otaria.OtariaTurnAction
import com.wingedsheep.engine.otaria.OtariaEncounterMana
import com.wingedsheep.engine.otaria.OtariaEncounterFlow
import com.wingedsheep.sdk.core.Step

internal class OtariaContinuationResumer(private val services: EngineServices) :
    ContinuationResumerModule, AutoResumerModule {
    private val encounterEntry = OtariaEncounterEntry(services.zones)

    override fun autoResumers(): List<AutoResumer<*>> = listOf(
        autoResumer(FinishOtariaEncounterEntryContinuation::class) { state, continuation, events, checkForMore ->
            val result = OtariaEncounterFlow.finishEntry(state, continuation.actorId)
            if (result.outcome is Outcome.Done) checkForMore(result.state, events + result.events)
            else result.copy(events = events + result.events)
        },
        autoResumer(ContinueOtariaEventEffectsContinuation::class) { state, continuation, events, checkForMore ->
            val result = services.turnManager.otariaPhaseActions.resolveEventEffects(
                state, continuation.explorerId, continuation.managerId, continuation.originalSourceId,
                continuation.sourceIds, continuation.nextIndex,
            )
            if (result.outcome is Outcome.Done) checkForMore(result.state, events + result.events)
            else result.copy(events = events + result.events)
        },
        autoResumer(FinishOtariaEventCopyContinuation::class) { state, continuation, events, checkForMore ->
            val result = services.turnManager.otariaPhaseActions.finishEventCopy(
                state, continuation.explorerId, continuation.sourceId,
            )
            if (result.outcome is Outcome.Done) checkForMore(result.state, events + result.events)
            else result.copy(events = events + result.events)
        },
        autoResumer(FinishOtariaEventContinuation::class) { state, continuation, events, checkForMore ->
            val result = services.turnManager.otariaPhaseActions.finishEvent(
                state, continuation.explorerId, continuation.sourceId,
            )
            if (result.outcome is Outcome.Done) checkForMore(result.state, events + result.events)
            else result.copy(events = events + result.events)
        },
    )

    override fun resumers(): List<ContinuationResumer<*>> = listOf(
        resumer(OtariaEncounterInitiativeContinuation::class) { state, continuation, response, checkForMore ->
            if (response !is OptionChosenResponse) {
                return@resumer ExecutionResult.error(state, "Expected an encounter initiative choice")
            }
            val result = OtariaEncounterFlow.chooseInitiative(state, continuation.actorId, response.optionIndex)
            if (result.outcome is Outcome.Done) checkForMore(result.state, result.events) else result
        },
        resumer(OtariaEncounterEntryCopyContinuation::class) { state, continuation, response, checkForMore ->
            val context = EffectCopyEntry.recordAnswer(
                state, continuation.context, continuation.entityId, continuation.replacement, response,
            ) ?: return@resumer ExecutionResult.error(state, "Expected an Otaria encounter copy selection")
            val result = encounterEntry.continueEntry(state, continuation.plan, context)
            if (result.outcome is Outcome.Done) checkForMore(result.state, result.events) else result
        },
        resumer(OtariaEncounterEntryChoiceContinuation::class) { state, continuation, response, checkForMore ->
            val context = EffectEntryChoices.recordAnswer(
                state, continuation.context, continuation.entityId, continuation.question, response,
            ) ?: return@resumer ExecutionResult.error(state, "Expected an Otaria encounter entry choice")
            val result = encounterEntry.continueEntry(state, continuation.plan, context)
            if (result.outcome is Outcome.Done) checkForMore(result.state, result.events) else result
        },
        resumer(OtariaEncounterRetaliationContinuation::class) { state, continuation, response, _ ->
            if (response !is DistributionResponse) {
                return@resumer ExecutionResult.error(state, "Expected an Otaria retaliation damage distribution")
            }
            val updated = state.updateEntity(continuation.creatureId) {
                it.with(com.wingedsheep.engine.state.components.combat.DamageAssignmentComponent(response.distribution))
            }
            services.combatManager.applyCombatDamage(updated, firstStrike = continuation.firstStrike)
        },
        resumer(OtariaEncounterEntryManaChoiceContinuation::class) { state, continuation, response, checkForMore ->
            val encounter = state.otaria?.encounters?.get(continuation.actorId)
            if (response !is ColorChosenResponse || encounter == null ||
                state.activePlayerId != encounter.explorerId || state.step != Step.OTARIA_EXPLORATION ||
                continuation.remaining <= 0 ||
                OtariaEncounterMana.needed(state, continuation.actorId, continuation.creature) != continuation.remaining
            ) {
                return@resumer ExecutionResult.error(state, "Expected an entering encounter's mana color")
            }
            val added = OtariaEncounterMana.add(state, continuation.actorId, continuation.creature, response.color)
            if (added.outcome !is Outcome.Done) return@resumer added
            val result = OtariaEncounterMana.chooseNextAtEntry(added.state, continuation.actorId)
                ?: ExecutionResult.success(added.state)
            val events = added.events + result.events
            if (result.outcome is Outcome.Done) checkForMore(result.state, events)
            else result.copy(events = events)
        },
        resumer(OtariaEncounterManaChoiceContinuation::class) { state, continuation, response, checkForMore ->
            if (response !is ColorChosenResponse || state.activePlayerId != continuation.actorId ||
                continuation.remaining <= 0
            ) {
                return@resumer ExecutionResult.error(state, "Expected an active encounter's mana color")
            }
            val added = OtariaEncounterMana.add(state, continuation.actorId, continuation.creature, response.color)
            if (added.outcome !is Outcome.Done) return@resumer added
            val choice = OtariaEncounterMana.chooseNext(added.state, continuation.actorId)
            if (choice != null && choice.outcome !is Outcome.Done) {
                return@resumer choice.copy(events = added.events + choice.events)
            }
            val result = services.turnManager.finishTurnStart(
                choice?.state ?: added.state,
                continuation.actorId,
                emptyList(),
            )
            val events = added.events + (choice?.events ?: emptyList()) + result.events
            if (result.outcome is Outcome.Done) checkForMore(result.state, events)
            else result.copy(events = events)
        },
        resumer(OtariaTurnChoiceContinuation::class) { state, continuation, response, checkForMore ->
            if (response !is OptionChosenResponse) {
                return@resumer ExecutionResult.error(state, "Expected an Otaria turn action")
            }
            val action = OtariaTurnAction.entries.getOrNull(response.optionIndex)
                ?: return@resumer ExecutionResult.error(state, "Invalid Otaria turn action")
            val mode = state.otaria
                ?: return@resumer ExecutionResult.error(state, "This is not an Otaria game")
            if (state.activePlayerId != continuation.playerId || continuation.playerId !in mode.players) {
                return@resumer ExecutionResult.error(state, "Only the active Otaria player can choose an action")
            }
            val chosen = state.copy(otaria = mode.copy(
                turn = OtariaTurn(continuation.playerId, state.turnNumber, action),
            ))
            val result = services.turnManager.finishTurnStart(chosen, continuation.playerId, emptyList())
            val events = listOf(OtariaTurnActionChosenEvent(continuation.playerId, action)) + result.events
            if (result.outcome is Outcome.Done) checkForMore(result.state, events)
            else result.copy(events = events)
        },
        resumer(OtariaMovementChoiceContinuation::class) { state, continuation, response, checkForMore ->
            if (response !is OptionChosenResponse) {
                return@resumer ExecutionResult.error(state, "Expected an Otaria movement destination")
            }
            val destination = continuation.destinations.getOrNull(response.optionIndex)
                ?: return@resumer ExecutionResult.error(state, "Invalid Otaria movement destination")
            val result = services.turnManager.otariaPhaseActions.move(state, continuation.playerId, destination)
            if (result.outcome is Outcome.Done) checkForMore(result.state, result.events) else result
        },
        resumer(OtariaPreparationChoiceContinuation::class) { state, continuation, response, checkForMore ->
            if (response !is CardsSelectedResponse) {
                return@resumer ExecutionResult.error(state, "Expected an Otaria reserve selection")
            }
            val result = services.turnManager.otariaPhaseActions.prepare(state, continuation.playerId, response.selectedCards)
            if (result.outcome is Outcome.Done) checkForMore(result.state, result.events) else result
        },
        resumer(OtariaExplorationChoiceContinuation::class) { state, continuation, response, checkForMore ->
            if (response !is OptionChosenResponse) {
                return@resumer ExecutionResult.error(state, "Expected an Otaria exploration choice")
            }
            val choice = continuation.choices.getOrNull(response.optionIndex)
                ?: return@resumer ExecutionResult.error(state, "Invalid Otaria exploration choice")
            val result = services.turnManager.otariaPhaseActions.explore(state, continuation.playerId, choice)
            if (result.outcome is Outcome.Done) checkForMore(result.state, result.events) else result
        },
        resumer(OtariaDiscoveryChoiceContinuation::class) { state, continuation, response, checkForMore ->
            if (response !is YesNoResponse) {
                return@resumer ExecutionResult.error(state, "Expected an Otaria location choice")
            }
            val result = services.turnManager.otariaPhaseActions.finishDiscovery(state, continuation.playerId, response.choice)
            if (result.outcome is Outcome.Done) checkForMore(result.state, result.events) else result
        },
    )
}
