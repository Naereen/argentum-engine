package com.wingedsheep.mtg.sets.definitions.tor.cards

import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.dsl.Conditions
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.dsl.grantedActivatedAbility
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.ConditionalStaticAbility
import com.wingedsheep.sdk.scripting.GrantActivatedAbility
import com.wingedsheep.sdk.scripting.GrantColor
import com.wingedsheep.sdk.scripting.ModifyStats
import com.wingedsheep.sdk.scripting.filters.unified.GroupFilter
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter

val PossessedCentaur = card("Possessed Centaur") {
    manaCost = "{2}{G}{G}"
    colorIdentity = "G"
    typeLine = "Creature — Centaur Horror"
    power = 3
    toughness = 3
    oracleText = "Trample\n" +
        "Threshold — As long as there are seven or more cards in your graveyard, this creature gets +1/+1, is black, and has \"{2}{B}, {T}: Destroy target green creature.\""

    keywords(Keyword.TRAMPLE)

    staticAbility {
        ability = ConditionalStaticAbility(ModifyStats(1, 1, GroupFilter.source()), Conditions.CardsInGraveyardAtLeast(7))
    }
    staticAbility {
        ability = ConditionalStaticAbility(GrantColor(Color.BLACK, GroupFilter.source()), Conditions.CardsInGraveyardAtLeast(7))
    }
    staticAbility {
        ability = ConditionalStaticAbility(
            GrantActivatedAbility(
                ability = grantedActivatedAbility {
                    cost = Costs.Composite(Costs.Mana("{2}{B}"), Costs.Tap)
                    val target = target(TargetFilter.Creature.withColor(Color.GREEN))
                    effect = Effects.Destroy(target)
                    description = "{2}{B}, {T}: Destroy target green creature."
                },
                filter = GroupFilter.source()
            ),
            Conditions.CardsInGraveyardAtLeast(7)
        )
    }

    metadata {
        rarity = Rarity.RARE
        collectorNumber = "137"
        artist = "Alex Horley-Orlandelli"
        imageUri = "https://cards.scryfall.io/normal/front/6/0/608e11b2-9636-48a0-8705-f5ce3bc98117.jpg?1783945140"
    }
}
