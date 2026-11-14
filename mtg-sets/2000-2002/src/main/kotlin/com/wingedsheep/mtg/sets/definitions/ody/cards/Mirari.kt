package com.wingedsheep.mtg.sets.definitions.ody.cards

import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.targets.EffectTarget

val Mirari = card("Mirari") {
    manaCost = "{5}"
    typeLine = "Legendary Artifact"
    oracleText =
        "Whenever you cast an instant or sorcery spell, you may pay {3}. If you do, copy that spell. You may choose new targets for the copy."

    triggeredAbility {
        trigger = Triggers.you.casts(GameObjectFilter.InstantOrSorcery)
        effect = Effects.MayPay(
            cost = ManaCost.parse("{3}"),
            then = Effects.CopyTargetSpell(EffectTarget.TriggeringEntity)
        )
    }

    metadata {
        rarity = Rarity.RARE
        collectorNumber = "303"
        artist = "Donato Giancola"
        flavorText = "\"It offers you what you want, not what you need.\"\n—Braids, dementia summoner"
        imageUri = "https://cards.scryfall.io/normal/front/3/c/3cbaad4f-fb66-4e56-b383-32f55552c8b2.jpg"
    }
}
