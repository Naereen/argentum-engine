package com.wingedsheep.engine.otaria

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.EntityId

/** Bridges resumable battlefield entry into Otaria's initiative and normal combat step machine. */
internal object OtariaEncounterFlow {
    fun finishEntry(state: GameState, actorId: EntityId): ExecutionResult {
        val mode = state.otaria ?: return ExecutionResult.error(state, "This is not an Otaria game")
        val encounter = mode.encounters[actorId]
            ?: return ExecutionResult.error(state, "The entering encounter is missing")
        val turn = mode.turn ?: return ExecutionResult.error(state, "No Otaria turn is active")
        if (state.activePlayerId != encounter.explorerId || state.step != Step.OTARIA_EXPLORATION ||
            turn.playerId != encounter.explorerId || turn.action != OtariaTurnAction.EXPLORE ||
            encounter.initiative != null
        ) return ExecutionResult.error(state, "The encounter cannot determine initiative now")

        val (plan, rng) = OtariaContestPlanner.initiative(state, encounter.primaryId)
        val staged = state.copy(rng = rng, otaria = mode.copy(
            encounters = mode.encounters + (actorId to encounter.copy(initiative = plan)),
            turn = turn.copy(
                explorationFinished = plan.first == OtariaInitiativeSide.ENCOUNTER,
                encounterInitiative = plan.first,
            ),
        ))
        val event = OtariaInitiativeDeterminedEvent(actorId, encounter.explorerId, plan)
        if (plan.first == OtariaInitiativeSide.ENCOUNTER) {
            return ExecutionResult.success(staged, listOf(event))
        }
        val choice = staged.suspendForDecision(
            question = { id ->
                ChooseOptionDecision(
                    id = id,
                    playerId = encounter.explorerId,
                    prompt = "You won initiative: fight or flee?",
                    context = DecisionContext(sourceName = "Explorer of Otaria"),
                    options = listOf("Fight the encounter", "Flee without a contest"),
                    optionMetadata = listOf(
                        OptionMetadata(id = "otaria-initiative:combat"),
                        OptionMetadata(id = "otaria-initiative:move"),
                    ),
                )
            },
            answer = OtariaEncounterInitiativeContinuation(actorId),
        )
        return choice.copy(events = listOf(event) + choice.events)
    }

    fun chooseInitiative(state: GameState, actorId: EntityId, index: Int): ExecutionResult {
        val mode = state.otaria ?: return ExecutionResult.error(state, "This is not an Otaria game")
        val encounter = mode.encounters[actorId]
            ?: return ExecutionResult.error(state, "The encounter is missing")
        val turn = mode.turn ?: return ExecutionResult.error(state, "No Otaria turn is active")
        if (index !in 0..1 || state.activePlayerId != encounter.explorerId ||
            state.step != Step.OTARIA_EXPLORATION || turn.explorationFinished ||
            turn.encounterInitiative != OtariaInitiativeSide.EXPLORER
        ) return ExecutionResult.error(state, "Invalid encounter initiative choice")
        val action = if (index == 0) OtariaTurnAction.COMBAT else OtariaTurnAction.MOVE
        return ExecutionResult.success(state.copy(otaria = mode.copy(
            turn = turn.copy(explorationFinished = true, encounterAction = action),
        )), listOf(OtariaTurnActionChosenEvent(encounter.explorerId, action)))
    }
}
