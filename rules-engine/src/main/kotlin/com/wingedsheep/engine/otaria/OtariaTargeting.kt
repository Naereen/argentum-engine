package com.wingedsheep.engine.otaria

import com.wingedsheep.engine.handlers.PredicateContext
import com.wingedsheep.engine.handlers.PredicateEvaluator
import com.wingedsheep.engine.mechanics.layers.ProjectedState
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.predicates.CardPredicate
import com.wingedsheep.sdk.scripting.predicates.ControllerPredicate

/** Otaria's legendary encounter target substitutions, from the explorer's point of view. */
internal object OtariaTargeting {
    fun isLegendaryEncounterCreature(state: GameState, chooserId: EntityId, targetId: EntityId): Boolean {
        val encounter = state.otaria?.encounters?.values?.firstOrNull {
            it.explorerId == chooserId && it.primaryId == targetId
        } ?: return false
        val reference = state.objectRef(targetId) ?: return false
        if (reference !in encounter.members || targetId !in state.getBattlefield()) return false

        val projected = state.projectedState
        return projected.isCreature(targetId) && projected.isLegendary(targetId)
    }

    fun matchesTargetFilter(
        state: GameState,
        projected: ProjectedState,
        targetId: EntityId,
        filter: GameObjectFilter,
        chooserId: EntityId,
        predicateEvaluator: PredicateEvaluator,
        context: PredicateContext,
    ): Boolean {
        val effectiveFilter = if (isLegendaryEncounterCreature(state, chooserId, targetId)) {
            filter.asEncounterPlaneswalkerFilter()
        } else {
            filter
        }
        return predicateEvaluator.matches(state, projected, targetId, effectiveFilter, context)
    }

    private fun GameObjectFilter.asEncounterPlaneswalkerFilter(): GameObjectFilter = copy(
        cardPredicates = cardPredicates.map { it.asEncounterPlaneswalkerPredicate() },
        controllerPredicate = controllerPredicate.takeUnless {
            it == ControllerPredicate.ControlledByOpponent
        },
        anyOf = anyOf.map { it.asEncounterPlaneswalkerFilter() },
    )

    private fun CardPredicate.asEncounterPlaneswalkerPredicate(): CardPredicate = when (this) {
        CardPredicate.IsCreature -> CardPredicate.Or(emptyList())
        CardPredicate.IsPlaneswalker, CardPredicate.IsNoncreature -> CardPredicate.And(emptyList())
        is CardPredicate.And -> copy(predicates = predicates.map { it.asEncounterPlaneswalkerPredicate() })
        is CardPredicate.Or -> copy(predicates = predicates.map { it.asEncounterPlaneswalkerPredicate() })
        is CardPredicate.Not -> copy(predicate = predicate.asEncounterPlaneswalkerPredicate())
        else -> this
    }
}
