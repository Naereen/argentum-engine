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
import com.wingedsheep.sdk.scripting.targets.TargetOpponent
import com.wingedsheep.sdk.scripting.targets.TargetOpponentOrPlaneswalker
import com.wingedsheep.sdk.scripting.targets.TargetOther
import com.wingedsheep.sdk.scripting.targets.TargetPermanentOrPlayer
import com.wingedsheep.sdk.scripting.targets.TargetRequirement

/** The source's space and its neighbours are the only spaces it can reach. */
internal object OtariaRange {
    private const val OPPONENT_REACH = 2L

    private const val PRIORITY_INFLUENCE_REACH = 2L

    /** Whether [participant] is within the active player's Otaria range of influence. */
    fun isInPriorityInfluence(state: GameState, participant: EntityId): Boolean {
        val mode = state.otaria ?: return true
        val source = mode.players[state.activePlayerId]?.position
            ?: mode.encounters[state.activePlayerId]?.position
            ?: return false
        val destination = mode.players[participant]?.position
            ?: mode.encounters[participant]?.position
            ?: return false
        return OtariaBoard.distance(source, destination) <= PRIORITY_INFLUENCE_REACH
    }

    fun betweenControllers(state: GameState, sourceController: EntityId?, targetController: EntityId?): Boolean {
        val mode = state.otaria ?: return true
        val source = mode.players[sourceController]?.position ?: mode.encounters[sourceController]?.position ?: return false
        val destination = mode.players[targetController]?.position ?: mode.encounters[targetController]?.position ?: return false
        return OtariaBoard.distance(source, destination) <= 1
    }

    /** Opponents on the same hex or within two hexes for opponent-scoped effects. */
    fun opponentsInReach(state: GameState, sourceController: EntityId): List<EntityId> {
        val opponents = state.getOpponents(sourceController)
        if (state.otaria == null) return opponents
        return opponents.filter { isWithinOpponentReach(state, sourceController, it) }
    }

    fun isOpponentInReach(state: GameState, sourceController: EntityId, playerId: EntityId): Boolean =
        playerId in opponentsInReach(state, sourceController)

    fun containsForTarget(
        state: GameState,
        sourceController: EntityId,
        target: EntityId,
        requirement: TargetRequirement?,
    ): Boolean {
        val opponentTargetRequirement = when (requirement) {
            is TargetOpponent, is TargetOpponentOrPlaneswalker -> true
            is TargetPermanentOrPlayer -> requirement.opponentsOnly
            is TargetOther -> return containsForTarget(state, sourceController, target, requirement.baseRequirement)
            else -> false
        }
        return if (opponentTargetRequirement && isOpponentPlayerTarget(state, sourceController, target)) {
            containsOpponentTarget(state, sourceController, target)
        } else {
            contains(state, sourceController, target)
        }
    }

    fun containsOpponentTarget(state: GameState, sourceController: EntityId, target: EntityId): Boolean {
        if (state.otaria == null) return true
        val targetController = if (target in state.getBattlefield()) {
            val projectedController = state.projectedState.getController(target)
            controllerInProjection(state, target, projectedController) ?: return false
        } else {
            target
        }
        return isWithinOpponentReach(state, sourceController, targetController)
    }

    private fun isOpponentPlayerTarget(state: GameState, sourceController: EntityId, target: EntityId): Boolean =
        target in state.turnOrder || OtariaTargeting.candidatePlayerId(state, sourceController, target) != null

    private fun isWithinOpponentReach(
        state: GameState,
        sourceController: EntityId,
        targetController: EntityId,
    ): Boolean {
        val mode = state.otaria ?: return true
        val source = mode.players[sourceController]?.position ?: mode.encounters[sourceController]?.position ?: return false
        val destination = mode.players[targetController]?.position ?: mode.encounters[targetController]?.position ?: return false
        return OtariaBoard.distance(source, destination) <= OPPONENT_REACH
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
