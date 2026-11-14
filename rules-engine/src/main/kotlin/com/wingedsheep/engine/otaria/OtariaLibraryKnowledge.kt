package com.wingedsheep.engine.otaria

import com.wingedsheep.engine.handlers.PredicateEvaluator
import com.wingedsheep.engine.handlers.effects.library.LibraryRevealUtils
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.LibraryPositionAnchor
import com.wingedsheep.engine.view.Visibility
import com.wingedsheep.sdk.core.Zone

/**
 * An active top-of-library permission can expose a card without an explicit reveal component.
 * Remember it between actions and effect instructions in Otaria, before later work can bury it
 * or remove the permission.
 */
internal class OtariaLibraryKnowledge(
    cardRegistry: CardRegistry,
    predicateEvaluator: PredicateEvaluator,
) {
    private val visibility = Visibility(cardRegistry, conditionEvaluator = predicateEvaluator.conditions)

    fun rememberVisibleTops(state: GameState): GameState {
        val mode = state.otaria ?: return state
        val players = state.activePlayers
        var current = state
        for (owner in mode.players.keys) {
            val zone = ZoneKey(owner, Zone.LIBRARY)
            val top = state.getZone(zone).firstOrNull() ?: continue
            val observers = players.filter {
                visibility.isCardIdentityVisibleTo(state, zone, top, it)
            }
            current = LibraryRevealUtils.markRevealed(current, listOf(top), observers, LibraryPositionAnchor.TOP)
        }
        return current
    }
}
