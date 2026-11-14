package com.wingedsheep.mtg.sets.definitions.ody.cards

import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.CounterType
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.dsl.Conditions
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.dsl.grantedActivatedAbility
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.ConditionalStaticAbility
import com.wingedsheep.sdk.scripting.GrantActivatedAbility
import com.wingedsheep.sdk.scripting.SetColors
import com.wingedsheep.sdk.scripting.filters.unified.GroupFilter
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter
import com.wingedsheep.sdk.scripting.targets.EffectTarget

val RepentantVampire = card("Repentant Vampire") {
    manaCost = "{3}{B}{B}"
    colorIdentity = "B"
    typeLine = "Creature — Vampire"
    power = 3
    toughness = 3
    oracleText = "Flying\n" +
        "Whenever a creature dealt damage by this creature this turn dies, put a +1/+1 counter on this creature.\n" +
        "Threshold — As long as there are seven or more cards in your graveyard, this creature is white and has " +
        "\"{T}: Destroy target black creature.\""

    keywords(Keyword.FLYING)

    triggeredAbility {
        trigger = Triggers.self.damagedCreatureDies()
        effect = Effects.AddCounters(
            CounterType.PLUS_ONE_PLUS_ONE,
            1,
            EffectTarget.Self
        )
    }

    staticAbility {
        ability = ConditionalStaticAbility(
            ability = SetColors(setOf(Color.WHITE), GroupFilter.source()),
            condition = Conditions.CardsInGraveyardAtLeast(7)
        )
    }

    staticAbility {
        ability = ConditionalStaticAbility(
            ability = GrantActivatedAbility(
                ability = grantedActivatedAbility {
                    cost = Costs.Tap
                    val creature = target(TargetFilter.Creature.withColor(Color.BLACK))
                    effect = Effects.Destroy(creature)
                },
                filter = GroupFilter.source()
            ),
            condition = Conditions.CardsInGraveyardAtLeast(7)
        )
    }

    metadata {
        rarity = Rarity.RARE
        collectorNumber = "157"
        artist = "Mark Tedin"
        imageUri = "https://cards.scryfall.io/normal/front/2/c/2c1dbad4-1105-4426-8d4d-a30f6fa95ce8.jpg?1783945240"
    }
}
