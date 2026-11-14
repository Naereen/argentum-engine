package com.wingedsheep.mtg.sets.definitions.ody.cards

import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.dsl.Filters
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.GrantProtectionFromCardType

val BelovedChaplain = card("Beloved Chaplain") {
    manaCost = "{1}{W}"
    colorIdentity = "W"
    typeLine = "Creature — Human Cleric"
    power = 1
    toughness = 1
    oracleText = "Protection from creatures"

    staticAbility {
        ability = GrantProtectionFromCardType(CardType.CREATURE, Filters.Self)
    }

    metadata {
        rarity = Rarity.UNCOMMON
        collectorNumber = "11"
        artist = "Darrell Riche"
        imageUri = "https://cards.scryfall.io/normal/front/d/e/decec62b-7ac4-4097-9215-5db18db2dec6.jpg?1783945282"
    }
}
