package com.wingedsheep.mtg.sets.definitions.ody.cards

import com.wingedsheep.sdk.dsl.Conditions
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.CantAttackUnless
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.filters.unified.GroupFilter
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter

val Dreamwinder = card("Dreamwinder") {
    manaCost = "{3}{U}"
    colorIdentity = "U"
    typeLine = "Creature — Serpent"
    power = 4
    toughness = 3
    oracleText = "This creature can't attack unless defending player controls an Island.\n" +
        "{U}, Sacrifice an Island: Target land becomes an Island until end of turn."

    staticAbility {
        ability = CantAttackUnless(
            condition = Conditions.DefendingPlayerControlsLandType("Island"),
            filter = GroupFilter.source()
        )
    }

    activatedAbility {
        cost = Costs.Composite(
            Costs.Mana("{U}"),
            Costs.SacrificePermanents(GameObjectFilter.Land.withSubtype("Island")),
        )
        val land = target(TargetFilter.Land)
        effect = Effects.SetLandType("Island", land)
    }

    metadata {
        rarity = Rarity.COMMON
        collectorNumber = "83"
        artist = "Wayne England"
        imageUri = "https://cards.scryfall.io/normal/front/2/a/2ae3a000-478c-4185-a8c0-82f29942497b.jpg?1783945260"
    }
}
