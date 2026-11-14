package com.wingedsheep.mtg.sets.definitions.ody.cards

import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Targets
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.effects.CardSource

val AvenWindreader = card("Aven Windreader") {
    manaCost = "{3}{U}{U}"
    colorIdentity = "U"
    typeLine = "Creature — Bird Soldier Wizard"
    power = 3
    toughness = 3
    oracleText = "Flying\n{1}{U}: Target player reveals the top card of their library."

    keywords(Keyword.FLYING)

    activatedAbility {
        cost = Costs.Mana("{1}{U}")
        val player = target(Targets.Player)
        effect = Effects.Pipeline {
            val topCard = gather(CardSource.TopOfLibrary(1, player.asPlayer))
            reveal(topCard)
        }
    }

    metadata {
        rarity = Rarity.COMMON
        collectorNumber = "65"
        artist = "Greg Hildebrandt"
        imageUri = "https://cards.scryfall.io/normal/front/9/f/9fd2f2cb-a0f1-4844-a692-bcaf1899d41c.jpg?1783945266"
    }
}
