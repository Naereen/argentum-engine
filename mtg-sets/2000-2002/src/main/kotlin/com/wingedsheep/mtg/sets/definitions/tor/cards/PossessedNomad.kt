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

val PossessedNomad = card("Possessed Nomad") {
    manaCost = "{2}{W}{W}"
    colorIdentity = "W"
    typeLine = "Creature — Human Nomad Horror"
    power = 3
    toughness = 3
    oracleText = "Vigilance\n" +
        "Threshold — As long as there are seven or more cards in your graveyard, this creature gets +1/+1, is black, and has \"{2}{B}, {T}: Destroy target white creature.\""

    keywords(Keyword.VIGILANCE)

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
                    val target = target(TargetFilter.Creature.withColor(Color.WHITE))
                    effect = Effects.Destroy(target)
                    description = "{2}{B}, {T}: Destroy target white creature."
                },
                filter = GroupFilter.source()
            ),
            Conditions.CardsInGraveyardAtLeast(7)
        )
    }

    metadata {
        rarity = Rarity.RARE
        collectorNumber = "13"
        artist = "Eric Peterson"
        imageUri = "https://cards.scryfall.io/normal/front/5/d/5d9dfae5-63f6-401a-9dec-afe838190824.jpg?1783945168"
    }
}
