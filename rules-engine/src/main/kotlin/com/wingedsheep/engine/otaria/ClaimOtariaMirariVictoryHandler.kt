package com.wingedsheep.engine.otaria

import com.wingedsheep.engine.core.ClaimOtariaMirariVictory
import com.wingedsheep.engine.core.ExecutionResult
import com.wingedsheep.engine.event.canTakePlayerActionAtPriority
import com.wingedsheep.engine.handlers.actions.ActionHandler
import com.wingedsheep.engine.state.GameState
import kotlin.reflect.KClass

internal class ClaimOtariaMirariVictoryHandler : ActionHandler<ClaimOtariaMirariVictory> {
    override val actionType: KClass<ClaimOtariaMirariVictory> = ClaimOtariaMirariVictory::class

    override fun validate(state: GameState, action: ClaimOtariaMirariVictory): String? {
        if (state.pendingDecision != null) return "A decision is pending"
        if (!canTakePlayerActionAtPriority(state, action.playerId)) return "You don't have priority"
        return OtariaRules.validateMirariVictory(state, action.playerId, action.mirariId)
    }

    override fun execute(state: GameState, action: ClaimOtariaMirariVictory): ExecutionResult =
        OtariaRules.claimMirariVictory(state, action.playerId, action.mirariId)
}
