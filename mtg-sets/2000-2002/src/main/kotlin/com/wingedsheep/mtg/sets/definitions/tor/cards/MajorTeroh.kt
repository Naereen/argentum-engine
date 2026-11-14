package com.wingedsheep.mtg.sets.definitions.tor.cards

import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.filters.unified.GroupFilter
import com.wingedsheep.sdk.scripting.targets.EffectTarget

val MajorTeroh = card("Major Teroh") {
    manaCost = "{3}{W}"
    colorIdentity = "W"
    typeLine = "Legendary Creature — Bird Soldier"
    power = 2
    toughness = 3
    oracleText = "Flying\n{3}{W}{W}, Sacrifice this creature: Exile all black creatures."

    keywords(com.wingedsheep.sdk.core.Keyword.FLYING)

    activatedAbility {
        cost = Costs.Composite(Costs.Mana("{3}{W}{W}"), Costs.SacrificeSelf)
        effect = Effects.ForEachInGroup(
            filter = GroupFilter(GameObjectFilter.Creature.withColor(Color.BLACK)),
            effect = Effects.Exile(EffectTarget.IterationEntity)
        )
    }

    metadata {
        rarity = Rarity.RARE
        collectorNumber = "8"
        artist = "Daren Bader"
        imageUri = "https://cards.scryfall.io/normal/front/3/2/3229ca5a-5340-48b8-bd46-b0b924c8faf7.jpg?1783945169"
    }
}
