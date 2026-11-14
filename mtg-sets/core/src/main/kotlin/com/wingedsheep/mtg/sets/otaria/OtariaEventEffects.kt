package com.wingedsheep.mtg.sets.otaria

import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.effects.Chooser
import com.wingedsheep.sdk.scripting.effects.Effect
import com.wingedsheep.sdk.scripting.filters.unified.GroupFilter
import com.wingedsheep.sdk.scripting.references.Player
import com.wingedsheep.sdk.scripting.targets.EffectTarget

/** Effects for the supplied event proxies that can be resolved by existing engine primitives. */
object OtariaEventEffects {
    private val naturalisation = Effects.Pipeline(stopOnError = true) {
        val candidates = gather(GameObjectFilter.ArtifactOrEnchantment, player = Player.You)
        val chosen = chooseExactly(
            1, candidates, chooser = Chooser.TriggeringPlayer,
            prompt = "Choose an artifact or enchantment controlled by the explorer",
            useTargetingUI = true,
        )
        destroy(chosen)
    }

    private val concertedStrike = Effects.Pipeline(stopOnError = true) {
        val candidates = gather(GameObjectFilter.Creature.powerAtMost(3), player = Player.You)
        val chosen = chooseExactly(
            1, candidates, chooser = Chooser.TriggeringPlayer,
            prompt = "Choose a creature with power 3 or less controlled by the explorer",
            useTargetingUI = true,
        )
        exile(chosen)
    }

    private val infestation = Effects.ForEachInGroup(
        GroupFilter.AllCreatures,
        Effects.ModifyStats(-2, -2, EffectTarget.IterationEntity),
    )

    private val pyroclasm = Effects.ForEachInGroup(
        GroupFilter.AllCreatures,
        Effects.DealDamage(2, EffectTarget.IterationEntity),
    )

    private val brainFreeze = Effects.Pipeline(stopOnError = true) {
        mill(3)
    }

    fun forName(name: String): Effect? = when (name) {
        "Ev. Naturalisation" -> naturalisation
        "Ev. Frappe Concertée" -> concertedStrike
        "Ev. Infestation" -> infestation
        "Ev. Pyroclasme" -> pyroclasm
        "Ev. Blocage cérébral" -> brainFreeze
        else -> null
    }
}
