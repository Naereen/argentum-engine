package com.wingedsheep.mtg.sets.definitions.ody.cards

import com.wingedsheep.sdk.dsl.DynamicAmounts
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.dsl.grantedTriggeredAbility
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter

val Spiritualize = card("Spiritualize") {
    manaCost = "{2}{W}"
    colorIdentity = "W"
    typeLine = "Instant"
    oracleText = "Until end of turn, whenever target creature deals damage, you gain that much life.\n" +
        "Draw a card."

    spell {
        val creature = target(TargetFilter.Creature)
        effect = Effects.GrantTriggeredAbility(
            ability = grantedTriggeredAbility {
                trigger = Triggers.self.dealsDamage()
                effect = Effects.GainLife(DynamicAmounts.triggerDamageAmount())
            },
            target = creature
        ) then Effects.DrawCards(1)
    }

    metadata {
        rarity = Rarity.UNCOMMON
        collectorNumber = "53"
        artist = "Christopher Moeller"
        imageUri = "https://cards.scryfall.io/normal/front/f/6/f6780ded-86df-419d-b63b-6b67d2960b28.jpg?1783945269"
    }
}
