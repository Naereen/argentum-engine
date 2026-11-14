package com.wingedsheep.mtg.sets.definitions.ody.cards

import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter

val Shelter = card("Shelter") {
    manaCost = "{1}{W}"
    colorIdentity = "W"
    typeLine = "Instant"
    oracleText = "Target creature you control gains protection from the color of your choice until end of turn.\n" +
        "Draw a card."

    spell {
        val creature = target(TargetFilter.CreatureYouControl)
        effect = Effects.ChooseColorThen(
            Effects.GrantProtectionFromChosenColor(creature)
        ) then Effects.DrawCards(1)
    }

    metadata {
        rarity = Rarity.COMMON
        collectorNumber = "46"
        artist = "Christopher Moeller"
        imageUri = "https://cards.scryfall.io/normal/front/9/c/9c726ab3-1692-4e14-ab21-664a1679bc53.jpg?1783945271"
    }
}
