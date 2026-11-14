package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.engine.otaria.OtariaRange
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.identity.faceUpCardDefinition
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.DampLandManaProduction

/**
 * Collect the sources once, then use the same spatial check for previews and actual production.
 */
internal object LandManaDampening {
    fun sources(state: GameState, registry: CardRegistry): List<EntityId> {
        if (state.otaria == null) {
            for (player in state.turnOrder) {
                for (id in state.getBattlefield(player)) {
                    val definition = state.getEntity(id)?.faceUpCardDefinition(registry) ?: continue
                    if (definition.script.staticAbilities.any { it is DampLandManaProduction }) return listOf(id)
                }
            }
            return emptyList()
        }
        return state.getBattlefield().filter { id ->
            if (state.projectedState.hasLostAllAbilities(id)) return@filter false
            val definition = state.getEntity(id)?.faceUpCardDefinition(registry) ?: return@filter false
            definition.script.staticAbilities.any { it is DampLandManaProduction }
        }
    }

    fun affects(state: GameState, sources: List<EntityId>, landId: EntityId): Boolean {
        if (sources.isEmpty()) return false
        if (state.otaria == null) return true
        val projected = state.projectedState
        val landController = projected.getController(landId)
            ?: state.getEntity(landId)?.get<ControllerComponent>()?.playerId
        return sources.any { source ->
            val sourceController = projected.getController(source)
                ?: state.getEntity(source)?.get<ControllerComponent>()?.playerId
            OtariaRange.betweenControllers(state, sourceController, landController)
        }
    }
}
