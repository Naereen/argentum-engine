package com.wingedsheep.mtg.sets.definitions.tor.cards

import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Patterns
import com.wingedsheep.sdk.dsl.Targets
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity

val AmbassadorLaquatus = card("Ambassador Laquatus") {
    manaCost = "{1}{U}{U}"
    colorIdentity = "U"
    typeLine = "Legendary Creature — Merfolk Wizard"
    power = 1
    toughness = 3
    oracleText = "{3}: Target player mills three cards."

    activatedAbility {
        cost = Costs.Mana("{3}")
        val player = target(Targets.Player)
        effect = Patterns.Library.mill(3, player)
    }

    metadata {
        rarity = Rarity.RARE
        collectorNumber = "23"
        artist = "Eric Peterson"
        imageUri = "https://cards.scryfall.io/normal/front/8/8/887cac61-2001-4bd1-aeeb-20149ebc5856.jpg?1783945165"
    }
}
