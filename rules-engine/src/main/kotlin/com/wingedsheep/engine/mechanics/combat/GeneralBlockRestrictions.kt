package com.wingedsheep.engine.mechanics.combat

import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.PredicateContext
import com.wingedsheep.engine.handlers.PredicateEvaluator
import com.wingedsheep.engine.mechanics.layers.ProjectedState
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.FaceDownComponent
import com.wingedsheep.engine.state.nameVisibleToAll
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.CantBlockUnless
import com.wingedsheep.sdk.scripting.CantBlockUnlessCoBlocker
import com.wingedsheep.sdk.scripting.StaticAbility
import com.wingedsheep.sdk.scripting.filters.unified.Scope

internal class GeneralBlockRestrictions(
    private val cardRegistry: CardRegistry,
    private val predicateEvaluator: PredicateEvaluator,
) {
    private fun abilities(state: GameState, projected: ProjectedState, creatureId: EntityId): List<StaticAbility> {
        val creature = state.getEntity(creatureId) ?: return emptyList()
        if (creature.has<FaceDownComponent>() || projected.hasLostAllAbilities(creatureId)) return emptyList()
        val card = creature.get<CardComponent>() ?: return emptyList()
        return cardRegistry.getCard(card.cardDefinitionId)?.staticAbilities.orEmpty() +
            state.grantedStaticAbilities.filter { it.entityId == creatureId }.map { it.ability }
    }

    fun conditionalError(state: GameState, projected: ProjectedState, creatureId: EntityId, controller: EntityId): String? {
        val context = EffectContext(sourceId = creatureId, controllerId = controller)
        val restriction = abilities(state, projected, creatureId).filterIsInstance<CantBlockUnless>()
            .firstOrNull {
                it.filter.scope is Scope.Self && !predicateEvaluator.conditions.evaluate(state, it.condition, context)
            } ?: return null
        return "${name(state, creatureId)} ${restriction.description}"
    }

    fun coBlockerError(
        state: GameState,
        projected: ProjectedState,
        creatureId: EntityId,
        blockerIds: Set<EntityId>,
    ): String? {
        val context = PredicateContext(controllerId = projected.getController(creatureId) ?: creatureId)
        val restriction = abilities(state, projected, creatureId).filterIsInstance<CantBlockUnlessCoBlocker>()
            .firstOrNull { restriction ->
                restriction.filter.scope is Scope.Self && blockerIds.none { otherId ->
                    otherId != creatureId &&
                        predicateEvaluator.matches(state, projected, otherId, restriction.coBlockerFilter, context)
                }
            } ?: return null
        return "${name(state, creatureId)} ${restriction.description}"
    }

    private fun name(state: GameState, creatureId: EntityId): String =
        nameVisibleToAll(state, creatureId, state.getEntity(creatureId)?.get<CardComponent>()?.name ?: "Creature")
}
