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
import com.wingedsheep.sdk.scripting.targets.EffectTarget

val PossessedAven = card("Possessed Aven") {
    manaCost = "{2}{U}{U}"
    colorIdentity = "U"
    typeLine = "Creature — Bird Soldier Horror"
    power = 3
    toughness = 3
    oracleText = "Flying\n" +
        "Threshold — As long as there are seven or more cards in your graveyard, this creature gets +1/+1, is black, and has \"{2}{B}, {T}: Destroy target blue creature.\""

    keywords(Keyword.FLYING)

    staticAbility {
        ability = ConditionalStaticAbility(
            ability = ModifyStats(1, 1, GroupFilter.source()),
            condition = Conditions.CardsInGraveyardAtLeast(7)
        )
    }
    staticAbility {
        ability = ConditionalStaticAbility(
            ability = GrantColor(Color.BLACK, GroupFilter.source()),
            condition = Conditions.CardsInGraveyardAtLeast(7)
        )
    }
    staticAbility {
        ability = ConditionalStaticAbility(
            ability = GrantActivatedAbility(
                ability = grantedActivatedAbility {
                    cost = Costs.Composite(Costs.Mana("{2}{B}"), Costs.Tap)
                    val target = target(TargetFilter.Creature.withColor(Color.BLUE))
                    effect = Effects.Destroy(target)
                    description = "{2}{B}, {T}: Destroy target blue creature."
                },
                filter = GroupFilter.source()
            ),
            condition = Conditions.CardsInGraveyardAtLeast(7)
        )
    }

    metadata {
        rarity = Rarity.RARE
        collectorNumber = "45"
        artist = "Scott M. Fischer"
        imageUri = "https://cards.scryfall.io/normal/front/9/9/997c56c7-c625-4209-b4b6-d274dee2db48.jpg?1783945161"
    }
}
