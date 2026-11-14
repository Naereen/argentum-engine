package com.wingedsheep.engine.otaria

import com.wingedsheep.engine.state.GameState
import com.wingedsheep.sdk.core.Step

/**
 * The ordinary step machine still performs each step's actions, priorities and triggers. This
 * policy changes only its natural successor during an Otaria player's current turn.
 */
internal object OtariaTurnPolicy {
    fun nextStep(state: GameState, step: Step): Step {
        val turn = state.otaria?.turn
        if (turn == null || turn.playerId != state.activePlayerId || turn.turnNumber != state.turnNumber) {
            return step.next()
        }
        return when (step) {
            Step.UPKEEP -> when (turn.action) {
                OtariaTurnAction.MOVE -> if (turn.movementAllowed) Step.PRECOMBAT_MAIN else Step.END
                OtariaTurnAction.EXPLORE -> Step.PRECOMBAT_MAIN
                OtariaTurnAction.PREPARE -> Step.OTARIA_PREPARATION
                OtariaTurnAction.COMBAT -> Step.DRAW
            }
            Step.PRECOMBAT_MAIN -> when (turn.action) {
                OtariaTurnAction.MOVE -> Step.OTARIA_MOVEMENT
                OtariaTurnAction.EXPLORE -> Step.OTARIA_EXPLORATION
                OtariaTurnAction.PREPARE -> Step.END
                OtariaTurnAction.COMBAT -> Step.BEGIN_COMBAT
            }
            Step.OTARIA_EXPLORATION ->
                when {
                    !turn.explorationFinished -> Step.END
                    turn.encounterInitiative == OtariaInitiativeSide.ENCOUNTER -> Step.END
                    turn.encounterAction == OtariaTurnAction.COMBAT -> Step.BEGIN_COMBAT
                    turn.encounterAction == OtariaTurnAction.MOVE -> Step.OTARIA_MOVEMENT
                    else -> Step.POSTCOMBAT_MAIN
                }
            else -> step.next()
        }
    }
}
