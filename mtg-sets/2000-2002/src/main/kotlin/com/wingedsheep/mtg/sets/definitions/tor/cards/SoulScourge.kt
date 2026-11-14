package com.wingedsheep.mtg.sets.definitions.tor.cards

import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Targets
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.effects.DelayedTriggerExpiry
import com.wingedsheep.sdk.scripting.targets.EffectTarget

val SoulScourge = card("Soul Scourge") {
    manaCost = "{4}{B}"
    colorIdentity = "B"
    typeLine = "Creature — Nightmare Horror"
    power = 3
    toughness = 2
    oracleText = "Flying\n" +
        "When this creature enters, target player loses 3 life.\n" +
        "When this creature leaves the battlefield, that player gains 3 life."

    keywords(Keyword.FLYING)

    triggeredAbility {
        trigger = Triggers.self.enters()
        val player = target(Targets.Player)
        effect = Effects.LoseLife(3, player) then
            Effects.CreateDelayedTrigger(
                effect = Effects.GainLife(3, EffectTarget.ContextTarget(0)),
                trigger = Triggers.self.leaves(),
                watchedTarget = EffectTarget.Self,
                fireOnce = true,
                expiry = DelayedTriggerExpiry.Never
            )
    }

    metadata {
        rarity = Rarity.COMMON
        collectorNumber = "85"
        artist = "Carl Critchlow"
        imageUri = "https://cards.scryfall.io/normal/front/4/a/4aa7d91b-cfbb-4da0-97fa-3c0a15f1dbb9.jpg?1783945152"
    }
}
