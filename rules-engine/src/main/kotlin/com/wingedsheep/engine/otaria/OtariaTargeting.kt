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
    fun encounterForLegendaryCreature(
        state: GameState,
        chooserId: EntityId,
        targetId: EntityId,
    ): OtariaEncounter? = state.otaria?.encounters?.values?.firstOrNull {
        it.explorerId == chooserId && it.primaryId == targetId
    }?.takeIf {
        val reference = state.objectRef(targetId) ?: return@takeIf false
        reference in it.members &&
            targetId in state.getBattlefield() &&
            state.projectedState.isCreature(targetId) &&
            state.projectedState.isLegendary(targetId)
    }

    fun candidatePlayerId(
        state: GameState,
        chooserId: EntityId,
        targetId: EntityId,
    ): EntityId? = encounterForLegendaryCreature(state, chooserId, targetId)?.managerId

    fun isLegendaryEncounterCreature(state: GameState, chooserId: EntityId, targetId: EntityId): Boolean {
        return encounterForLegendaryCreature(state, chooserId, targetId) != null
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
