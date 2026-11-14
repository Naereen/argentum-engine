package com.wingedsheep.mtg.sets.definitions.jud.cards

import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.dsl.Conditions
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.dsl.grantedActivatedAbility
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.ConditionalStaticAbility
import com.wingedsheep.sdk.scripting.GrantActivatedAbility
import com.wingedsheep.sdk.scripting.ModifyStats
import com.wingedsheep.sdk.scripting.filters.unified.GroupFilter
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter

/**
 * Vigilant Sentry — Judgment #33
 * {1}{W}{W} · Creature — Human Nomad · 2/2
 * Threshold grants +1/+1 and "{T}: Target attacking or blocking creature gets +3/+3."
 */
val VigilantSentry = card("Vigilant Sentry") {
    manaCost = "{1}{W}{W}"
    colorIdentity = "W"
    typeLine = "Creature — Human Nomad"
    oracleText = "Threshold — As long as there are seven or more cards in your graveyard, this creature gets +1/+1 " +
        "and has \"{T}: Target attacking or blocking creature gets +3/+3 until end of turn.\""
    power = 2
    toughness = 2

    staticAbility {
        ability = ConditionalStaticAbility(
            ability = ModifyStats(powerBonus = 1, toughnessBonus = 1, filter = GroupFilter.source()),
            condition = Conditions.CardsInGraveyardAtLeast(7),
        )
    }
    staticAbility {
        ability = ConditionalStaticAbility(
            ability = GrantActivatedAbility(
                ability = grantedActivatedAbility {
                    cost = Costs.Tap
                    val targetCreature = target(TargetFilter.AttackingOrBlockingCreature)
                    effect = Effects.ModifyStats(3, 3, targetCreature)
                    description = "{T}: Target attacking or blocking creature gets +3/+3 until end of turn."
                },
                filter = GroupFilter.source(),
            ),
            condition = Conditions.CardsInGraveyardAtLeast(7),
        )
    }

    metadata {
        rarity = Rarity.COMMON
        collectorNumber = "33"
        artist = "Eric Peterson"
        imageUri = "https://cards.scryfall.io/normal/front/c/3/c3790282-ea04-4600-8912-dac541ffd081.jpg?1783945130"
    }
}
