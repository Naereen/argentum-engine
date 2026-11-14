package com.wingedsheep.mtg.sets.definitions.tor.cards

import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Conditions
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.dsl.grantedTriggeredAbility
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.scripting.ConditionalStaticAbility
import com.wingedsheep.sdk.scripting.GrantTriggeredAbility
import com.wingedsheep.sdk.scripting.filters.unified.GroupFilter

val RebornHero = card("Reborn Hero") {
    manaCost = "{2}{W}"
    colorIdentity = "W"
    typeLine = "Creature — Human Soldier"
    power = 2
    toughness = 2
    oracleText = "Vigilance\n" +
        "Threshold — As long as there are seven or more cards in your graveyard, this creature has \"When this creature dies, you may pay {W}{W}. If you do, return this card to the battlefield under your control.\""

    keywords(Keyword.VIGILANCE)

    staticAbility {
        ability = ConditionalStaticAbility(
            ability = GrantTriggeredAbility(
                ability = grantedTriggeredAbility {
                    trigger = com.wingedsheep.sdk.dsl.Triggers.self.dies()
                    effect = Effects.MayPay(
                        ManaCost.parse("{W}{W}"),
                        Effects.Move(
                            com.wingedsheep.sdk.scripting.targets.EffectTarget.Self,
                            Zone.BATTLEFIELD,
                            fromZone = Zone.GRAVEYARD
                        )
                    )
                },
                filter = GroupFilter.source()
            ),
            condition = Conditions.CardsInGraveyardAtLeast(7)
        )
    }

    metadata {
        rarity = Rarity.RARE
        collectorNumber = "14"
        artist = "Gary Ruddell"
        imageUri = "https://cards.scryfall.io/normal/front/b/4/b4acbc7f-c86e-4285-a66d-d7b03fe44377.jpg?1783945168"
    }
}
