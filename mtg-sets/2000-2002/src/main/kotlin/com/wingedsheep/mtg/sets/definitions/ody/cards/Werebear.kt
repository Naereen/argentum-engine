package com.wingedsheep.mtg.sets.definitions.ody.cards

import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.dsl.Conditions
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.ConditionalStaticAbility
import com.wingedsheep.sdk.scripting.ModifyStats
import com.wingedsheep.sdk.scripting.TimingRule
import com.wingedsheep.sdk.scripting.filters.unified.GroupFilter

val Werebear = card("Werebear") {
    manaCost = "{1}{G}"
    colorIdentity = "G"
    typeLine = "Creature — Human Bear Druid"
    power = 1
    toughness = 1
    oracleText = "{T}: Add {G}.\n" +
        "Threshold — This creature gets +3/+3 as long as there are seven or more cards in your graveyard."

    activatedAbility {
        cost = Costs.Tap
        effect = Effects.AddMana(Color.GREEN)
        manaAbility = true
        timing = TimingRule.ManaAbility
    }

    staticAbility {
        ability = ConditionalStaticAbility(
            ability = ModifyStats(powerBonus = 3, toughnessBonus = 3, filter = GroupFilter.source()),
            condition = Conditions.CardsInGraveyardAtLeast(7)
        )
    }

    metadata {
        rarity = Rarity.COMMON
        collectorNumber = "282"
        artist = "Carl Critchlow"
        imageUri = "https://cards.scryfall.io/normal/front/9/6/964cf7e3-932d-432f-8ad4-9bd651aada96.jpg?1783945208"
    }
}
