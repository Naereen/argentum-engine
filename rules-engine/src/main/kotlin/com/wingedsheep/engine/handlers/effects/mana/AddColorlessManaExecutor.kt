package com.wingedsheep.engine.handlers.effects.mana

import com.wingedsheep.engine.mechanics.mana.ChosenTypeManaRestriction
import com.wingedsheep.engine.core.EffectResult
import com.wingedsheep.engine.core.ManaPoolChangedEvent
import com.wingedsheep.engine.handlers.DynamicAmountEvaluator
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.effects.EffectExecutor
import com.wingedsheep.engine.otaria.OtariaEncounterMana
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.sdk.scripting.effects.AddColorlessManaEffect
import com.wingedsheep.sdk.scripting.effects.ManaExpiry
import com.wingedsheep.sdk.scripting.effects.ManaRestriction
import kotlin.reflect.KClass

/**
 * Executor for AddColorlessManaEffect.
 * "Add {C}{C}" or "Add an amount of {C} equal to..."
 */
class AddColorlessManaExecutor(
    private val amountEvaluator: DynamicAmountEvaluator
) : EffectExecutor<AddColorlessManaEffect> {

    override val effectType: KClass<AddColorlessManaEffect> = AddColorlessManaEffect::class

    override fun execute(
        state: GameState,
        effect: AddColorlessManaEffect,
        context: EffectContext
    ): EffectResult {
        val amount = amountEvaluator.evaluate(state, effect.amount, context)
        if (amount <= 0) {
            return EffectResult.success(state)
        }

        val encounterOwner = if (OtariaEncounterMana.isEncounter(state, context.controllerId)) {
            if (context.sourceReferenceLost) return EffectResult.error(state, "The Otaria mana source has left")
            OtariaEncounterMana.sourceOwner(
                state, context.controllerId, context.sourceId, context.objectReferences.origin,
            ) ?: return EffectResult.error(state, "Otaria mana needs an assigned encounter creature")
        } else null
        val restriction = ChosenTypeManaRestriction.bind(effect.restriction, state, context.sourceId)
        val tagged = encounterOwner != null || restriction != null ||
            effect.riders.isNotEmpty() || effect.expiry != ManaExpiry.END_OF_TURN
        var newState = state.updateEntity(context.controllerId) { container ->
            val manaPool = container.get<ManaPoolComponent>() ?: ManaPoolComponent()
            val updatedPool = if (tagged) {
                manaPool.addRestricted(
                    null, amount, restriction ?: ManaRestriction.AnySpend, effect.riders,
                    expiry = effect.expiry, assignedPermanent = encounterOwner,
                )
            } else {
                manaPool.addColorless(amount)
            }
            container.with(updatedPool)
        }

        newState = if (!tagged) {
            ManaProvenanceTracker.tagAddedMana(newState, context.controllerId, context.sourceId, amount, null, context)
        } else {
            ManaProvenanceTracker.tagAddedRestrictedMana(newState, context.controllerId, context.sourceId, amount, context)
        }

        return EffectResult.success(newState, listOf(ManaPoolChangedEvent(context.controllerId)))
    }
}
