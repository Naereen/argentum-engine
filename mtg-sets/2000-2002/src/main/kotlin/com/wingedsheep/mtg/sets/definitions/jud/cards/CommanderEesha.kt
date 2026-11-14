package com.wingedsheep.mtg.sets.definitions.jud.cards

import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.GrantProtectionFromCardType
import com.wingedsheep.sdk.scripting.filters.unified.GroupFilter

/**
 * Commander Eesha — Judgment #9
 * {2}{W}{W} · Legendary Creature — Bird Soldier · 2/4
 * Flying, protection from creatures.
 */
val CommanderEesha = card("Commander Eesha") {
    manaCost = "{2}{W}{W}"
    colorIdentity = "W"
    typeLine = "Legendary Creature — Bird Soldier"
    oracleText = "Flying, protection from creatures"
    power = 2
    toughness = 4

    keywords(Keyword.FLYING)
    staticAbility {
        ability = GrantProtectionFromCardType(CardType.CREATURE, GroupFilter.source())
    }

    metadata {
        rarity = Rarity.RARE
        collectorNumber = "9"
        artist = "Rebecca Guay"
        imageUri = "https://cards.scryfall.io/normal/front/3/6/3607f6a9-b8d2-4119-9f70-95dcedc0662d.jpg?1783945137"
    }
}
