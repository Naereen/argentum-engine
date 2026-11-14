package com.wingedsheep.mtg.sets.definitions.jud.cards

import com.wingedsheep.sdk.dsl.Conditions
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.ConditionalStaticAbility
import com.wingedsheep.sdk.scripting.ModifyStats
import com.wingedsheep.sdk.scripting.filters.unified.GroupFilter

/**
 * Anurid Barkripper — Judgment #104
 * {1}{G}{G} · Creature — Frog Beast · 2/2
 * Threshold — gets +2/+2 with seven or more cards in your graveyard.
 */
val AnuridBarkripper = card("Anurid Barkripper") {
    manaCost = "{1}{G}{G}"
    colorIdentity = "G"
    typeLine = "Creature — Frog Beast"
    oracleText = "Threshold — This creature gets +2/+2 as long as there are seven or more cards in your graveyard."
    power = 2
    toughness = 2

    staticAbility {
        ability = ConditionalStaticAbility(
            ability = ModifyStats(powerBonus = 2, toughnessBonus = 2, filter = GroupFilter.source()),
            condition = Conditions.CardsInGraveyardAtLeast(7),
        )
    }

    metadata {
        rarity = Rarity.COMMON
        collectorNumber = "104"
        artist = "Randy Gallegos"
        imageUri = "https://cards.scryfall.io/normal/front/3/3/33255dfd-f8a9-4a15-aac5-c53dc0257859.jpg?1783945114"
    }
}
