package com.wingedsheep.mtg.sets.definitions.ody.cards

import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.dsl.Conditions
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.ConditionalStaticAbility
import com.wingedsheep.sdk.scripting.GrantKeyword
import com.wingedsheep.sdk.scripting.ModifyStats
import com.wingedsheep.sdk.scripting.filters.unified.GroupFilter

val MysticZealot = card("Mystic Zealot") {
    manaCost = "{3}{W}"
    colorIdentity = "W"
    typeLine = "Creature — Human Nomad Mystic"
    power = 2
    toughness = 4
    oracleText = "Threshold — As long as there are seven or more cards in your graveyard, " +
        "this creature gets +1/+1 and has flying."

    staticAbility {
        ability = ConditionalStaticAbility(
            ability = ModifyStats(powerBonus = 1, toughnessBonus = 1, filter = GroupFilter.source()),
            condition = Conditions.CardsInGraveyardAtLeast(7)
        )
    }

    staticAbility {
        ability = ConditionalStaticAbility(
            ability = GrantKeyword(Keyword.FLYING, GroupFilter.source()),
            condition = Conditions.CardsInGraveyardAtLeast(7)
        )
    }

    metadata {
        rarity = Rarity.COMMON
        collectorNumber = "36"
        artist = "Paolo Parente"
        imageUri = "https://cards.scryfall.io/normal/front/1/b/1ba21062-5d13-47b9-bea1-ded8530a9aeb.jpg?1783945273"
    }
}
