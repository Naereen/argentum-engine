package com.wingedsheep.mtg.sets.definitions.ody.cards

import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.dsl.DynamicAmounts
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.ModifyMaximumHandSize
import com.wingedsheep.sdk.scripting.references.Player

val ThoughtNibbler = card("Thought Nibbler") {
    manaCost = "{U}"
    colorIdentity = "U"
    typeLine = "Creature — Beast"
    power = 1
    toughness = 1
    oracleText = "Flying\nYour maximum hand size is reduced by two."

    keywords(Keyword.FLYING)

    staticAbility {
        ability = ModifyMaximumHandSize(
            player = Player.You,
            amount = DynamicAmounts.fixed(-2)
        )
    }

    metadata {
        rarity = Rarity.COMMON
        collectorNumber = "107"
        artist = "Arnie Swekel"
        imageUri = "https://cards.scryfall.io/normal/front/7/2/7284a7fd-cda8-43ac-b119-ad47b33c2ec4.jpg?1783945254"
    }
}
