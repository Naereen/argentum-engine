package com.wingedsheep.engine.legalactions.enumerators

import com.wingedsheep.engine.core.ClaimOtariaMirariVictory
import com.wingedsheep.engine.event.canTakePlayerActionAtPriority
import com.wingedsheep.engine.legalactions.ActionEnumerator
import com.wingedsheep.engine.legalactions.EnumerationContext
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.otaria.OtariaRules

internal class OtariaVictoryEnumerator : ActionEnumerator {
    override fun enumerate(context: EnumerationContext): List<LegalAction> {
        val state = context.state
        val playerId = context.playerId
        if (state.otaria == null || state.pendingDecision != null ||
            !canTakePlayerActionAtPriority(state, playerId)
        ) return emptyList()
        return OtariaRules.mirariVictoryCandidates(state, playerId).map { mirariId ->
            LegalAction(
                action = ClaimOtariaMirariVictory(playerId, mirariId),
                actionType = "ClaimOtariaMirariVictory",
                description = "Reveal Mirari and claim victory",
            )
        }
    }
}
