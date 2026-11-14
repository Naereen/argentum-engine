package com.wingedsheep.mtg.sets.definitions.ody.cards

import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.targets.EffectTarget

val ResilientWanderer = card("Resilient Wanderer") {
    manaCost = "{2}{W}{W}"
    colorIdentity = "W"
    typeLine = "Creature — Human Nomad"
    power = 2
    toughness = 3
    oracleText = "First strike\n" +
        "Discard a card: This creature gains protection from the color of your choice until end of turn."

    keywords(Keyword.FIRST_STRIKE)

    activatedAbility {
        cost = Costs.DiscardCard
        effect = Effects.ChooseColorThen(
            Effects.GrantProtectionFromChosenColor(EffectTarget.Self)
        )
    }

    metadata {
        rarity = Rarity.UNCOMMON
        collectorNumber = "43"
        artist = "Clyde Caldwell"
        imageUri = "https://cards.scryfall.io/normal/front/5/e/5e5a61de-ee4e-4097-b342-b5f2c976e16e.jpg?1783945273"
    }
}
