package com.wingedsheep.engine.otaria

import com.wingedsheep.engine.handlers.PredicateEvaluator
import com.wingedsheep.engine.mechanics.combat.GeneralBlockRestrictions
import com.wingedsheep.engine.mechanics.layers.ProjectedState
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.combat.AttackingComponent
import com.wingedsheep.engine.state.components.combat.BeingAttackedComponent
import com.wingedsheep.engine.state.components.combat.BlockedComponent
import com.wingedsheep.engine.state.components.combat.BlockingComponent
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.model.EntityId

/** Combat relationships unique to an explored creature, without treating it as a blocker. */
internal object OtariaEncounterCombat {
    fun actorOfCreature(state: GameState, projected: ProjectedState, creatureId: EntityId): EntityId? {
        if (state.otaria == null || creatureId !in state.getBattlefield() || !projected.isCreature(creatureId)) {
            return null
        }
        val actor = projected.getController(creatureId) ?: return null
        val encounter = state.otaria.encounters[actor] ?: return null
        val reference = state.objectRef(creatureId) ?: return null
        return actor.takeIf { reference in encounter.members }
    }

    fun isBeingAttacked(state: GameState, creatureId: EntityId): Boolean =
        actorOfCreature(state, state.projectedState, creatureId) != null &&
            state.getEntity(creatureId)?.has<BeingAttackedComponent>() == true

    fun retaliationTargets(state: GameState, projected: ProjectedState, defenderId: EntityId): List<EntityId> {
        if (!isBeingAttacked(state, defenderId)) return emptyList()
        return state.getBattlefield().filter { attackerId ->
            val attacker = state.getEntity(attackerId) ?: return@filter false
            val attack = attacker.get<AttackingComponent>() ?: return@filter false
            attack.defenderId == defenderId && !attack.attackTargetRemoved &&
                (!attacker.has<BlockedComponent>() || projected.hasKeyword(attackerId, Keyword.TRAMPLE))
        }
    }

    fun canRetaliate(
        state: GameState,
        projected: ProjectedState,
        creatureId: EntityId,
        predicateEvaluator: PredicateEvaluator,
        cardRegistry: CardRegistry,
    ): Boolean {
        if (projected.cantBlock(creatureId)) return false
        val controller = projected.getController(creatureId) ?: return false
        val restrictions = GeneralBlockRestrictions(cardRegistry, predicateEvaluator)
        if (restrictions.conditionalError(state, projected, creatureId, controller) != null) return false
        val team = state.sharedTurnTeam(controller)
        val blockers = state.findEntitiesWith<BlockingComponent>().mapNotNull { (id, _) ->
            id.takeIf { projected.getController(it) in team }
        }.toSet()
        return restrictions.coBlockerError(state, projected, creatureId, blockers) == null
    }

    fun retaliatingCreatures(
        state: GameState,
        projected: ProjectedState,
        predicateEvaluator: PredicateEvaluator,
        cardRegistry: CardRegistry,
    ): List<EntityId> = state.findEntitiesWith<AttackingComponent>()
        .map { it.second.defenderId }
        .distinct()
        .filter {
            retaliationTargets(state, projected, it).isNotEmpty() &&
                canRetaliate(state, projected, it, predicateEvaluator, cardRegistry)
        }
}
