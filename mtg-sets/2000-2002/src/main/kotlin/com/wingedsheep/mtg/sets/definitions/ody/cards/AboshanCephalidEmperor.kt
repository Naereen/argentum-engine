package com.wingedsheep.mtg.sets.definitions.ody.cards

import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.filters.unified.GroupFilter
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter
import com.wingedsheep.sdk.scripting.targets.EffectTarget

val AboshanCephalidEmperor = card("Aboshan, Cephalid Emperor") {
    manaCost = "{4}{U}{U}"
    colorIdentity = "U"
    typeLine = "Legendary Creature — Octopus Noble"
    oracleText = "Tap an untapped Octopus you control: Tap target permanent.\n{U}{U}{U}: Tap all creatures without flying."
    power = 3
    toughness = 3
    activatedAbility {
        cost = Costs.TapPermanents(1, GameObjectFilter.Creature.withSubtype("Octopus"))
        val target = target(TargetFilter.Permanent)
        effect = Effects.Tap(target)
    }
    activatedAbility {
        cost = Costs.Mana("{U}{U}{U}")
        effect = Effects.ForEachInGroup(
            GroupFilter(GameObjectFilter.Creature.withoutKeyword(Keyword.FLYING)),
            Effects.Tap(EffectTarget.IterationEntity)
        )
    }
    metadata {
        rarity = Rarity.RARE
        collectorNumber = "58"
        artist = "Christopher Moeller"
        flavorText = "\"No one can fight the tide forever.\""
        imageUri = "https://cards.scryfall.io/normal/front/8/2/82db4a41-03e8-4f0c-946c-a98fc5c9f7c8.jpg"
    }
}
