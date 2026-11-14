package com.wingedsheep.mtg.sets.definitions.ody.cards

import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.DynamicAmounts
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter

val NantukoMentor = card("Nantuko Mentor") {
    manaCost = "{2}{G}"
    colorIdentity = "G"
    typeLine = "Creature — Insect Druid"
    power = 1
    toughness = 1
    oracleText = "{2}{G}, {T}: Target creature gets +X/+X until end of turn, where X is that creature's power."

    activatedAbility {
        cost = Costs.Composite(Costs.Mana("{2}{G}"), Costs.Tap)
        val creature = target(TargetFilter.Creature)
        val power = DynamicAmounts.targetPower()
        effect = Effects.ModifyStats(power, power, creature)
    }

    metadata {
        rarity = Rarity.RARE
        collectorNumber = "255"
        artist = "John Matson"
        imageUri = "https://cards.scryfall.io/normal/front/4/8/48cb5283-d384-490e-a0a5-2d10c1acc8cc.jpg?1783945213"
    }
}
