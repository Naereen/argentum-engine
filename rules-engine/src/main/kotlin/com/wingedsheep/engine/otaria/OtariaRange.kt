package com.wingedsheep.engine.otaria

import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.CopyOfComponent
import com.wingedsheep.engine.state.components.stack.AbilityOnStackComponent
import com.wingedsheep.engine.state.components.stack.ActivatedAbilityOnStackComponent
import com.wingedsheep.engine.state.components.stack.SpellOnStackComponent
import com.wingedsheep.engine.state.components.stack.TriggeredAbilityOnStackComponent
import com.wingedsheep.sdk.model.EntityId

/** The source's space and its neighbours are the only spaces it can reach. */
internal object OtariaRange {
    fun betweenControllers(state: GameState, sourceController: EntityId?, targetController: EntityId?): Boolean {
        val mode = state.otaria ?: return true
        val source = mode.players[sourceController]?.position ?: mode.encounters[sourceController]?.position ?: return false
        val destination = mode.players[targetController]?.position ?: mode.encounters[targetController]?.position ?: return false
        return OtariaBoard.distance(source, destination) <= 1
    }

    fun contains(state: GameState, sourceController: EntityId, target: EntityId): Boolean {
        if (state.otaria == null) return true
        val projectedController = if (target in state.getBattlefield()) {
            state.projectedState.getController(target)
        } else null
        return betweenControllers(state, sourceController, controllerInProjection(state, target, projectedController))
    }

    fun containsForCombat(state: GameState, sourceController: EntityId, target: EntityId): Boolean {
        return contains(state, sourceController, target)
    }

    fun observesFaceUp(
        state: GameState,
        sourceId: EntityId,
        sourceController: EntityId,
        eventController: EntityId,
    ): Boolean {
        if (betweenControllers(state, sourceController, eventController)) return true
        val mode = state.otaria ?: return true
        val encounter = mode.encounters[sourceController] ?: return false
        val sourceRef = state.objectRef(sourceId) ?: return false
        if (encounter.primaryId != sourceId || sourceId !in state.getBattlefield() ||
            sourceRef !in encounter.members
        ) return false
        val source = state.getEntity(sourceId) ?: return false
        val original = source.get<CopyOfComponent>()?.originalCardComponent ?: source.get<CardComponent>()
        return original != null && mode.encounterSupport.forCard(encounter.source, original.name)
            ?.faceUpTriggerIgnoresRange == true
    }

    /**
     * Resolve a target's location without accessing the lazy projection. Layer application passes
     * its in-progress controller; spells and abilities on the stack carry their own controller.
     */
    fun controllerInProjection(state: GameState, target: EntityId, projectedController: EntityId?): EntityId? {
        val mode = state.otaria ?: return projectedController
        return when {
            target in mode.players || target in mode.encounters -> target
            target in state.getBattlefield() ->
                projectedController ?: state.getEntity(target)?.get<ControllerComponent>()?.playerId
            target in state.stack -> state.getEntity(target)?.let { entity ->
                entity.get<SpellOnStackComponent>()?.casterId
                    ?: entity.get<ActivatedAbilityOnStackComponent>()?.controllerId
                    ?: entity.get<TriggeredAbilityOnStackComponent>()?.controllerId
                    ?: entity.get<AbilityOnStackComponent>()?.controllerId
            }
            else -> state.logicalZone(target)?.ownerId
        }
    }

    fun containsInProjection(
        state: GameState,
        sourceController: EntityId?,
        target: EntityId,
        projectedController: EntityId?,
    ): Boolean {
        if (state.otaria == null) return true
        return betweenControllers(state, sourceController, controllerInProjection(state, target, projectedController))
    }
}
