package com.wingedsheep.mtg.sets.definitions.tor.cards

import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.dsl.Conditions
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.ConditionalStaticAbility
import com.wingedsheep.sdk.scripting.ModifyStats
import com.wingedsheep.sdk.scripting.filters.unified.GroupFilter

val SetonsScout = card("Seton's Scout") {
    manaCost = "{1}{G}"
    colorIdentity = "G"
    typeLine = "Creature — Centaur Druid Scout Archer"
    power = 2
    toughness = 1
    oracleText = "Reach\nThreshold — This creature gets +2/+2 as long as there are seven or more cards in your graveyard."

    keywords(Keyword.REACH)

    staticAbility {
        ability = ConditionalStaticAbility(
            ability = ModifyStats(2, 2, GroupFilter.source()),
            condition = Conditions.CardsInGraveyardAtLeast(7)
        )
    }

    metadata {
        rarity = Rarity.UNCOMMON
        collectorNumber = "138"
        artist = "Mark Romanoski"
        imageUri = "https://cards.scryfall.io/normal/front/7/3/732bf7bb-5326-4742-8311-96ddbfe14b38.jpg?1783945140"
    }
}
