package com.wingedsheep.mtg.sets.definitions.ody.cards

import com.wingedsheep.sdk.dsl.Conditions
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.ConditionalStaticAbility
import com.wingedsheep.sdk.scripting.ModifyStats
import com.wingedsheep.sdk.scripting.filters.unified.GroupFilter

val SpringingTiger = card("Springing Tiger") {
    manaCost = "{3}{G}"
    colorIdentity = "G"
    typeLine = "Creature — Cat"
    power = 3
    toughness = 3
    oracleText = "Threshold — This creature gets +2/+2 as long as there are seven or more cards in your graveyard."

    staticAbility {
        ability = ConditionalStaticAbility(
            ability = ModifyStats(powerBonus = 2, toughnessBonus = 2, filter = GroupFilter.source()),
            condition = Conditions.CardsInGraveyardAtLeast(7)
        )
    }

    metadata {
        rarity = Rarity.COMMON
        collectorNumber = "272"
        artist = "Arnie Swekel"
        imageUri = "https://cards.scryfall.io/normal/front/0/4/04462629-fb03-40e8-84e6-4a66e4d5392b.jpg?1783945210"
    }
}
