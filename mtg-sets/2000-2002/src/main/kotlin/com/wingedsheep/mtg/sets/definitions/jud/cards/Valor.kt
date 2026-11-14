package com.wingedsheep.mtg.sets.definitions.jud.cards

import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Conditions
import com.wingedsheep.sdk.dsl.Filters
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.GrantKeyword
import com.wingedsheep.sdk.scripting.filters.unified.GroupFilter

/**
 * Valor — Judgment #32
 * {3}{W} · Creature — Incarnation · 2/2
 * First strike; from your graveyard, grants first strike to your creatures while you control a Plains.
 */
val Valor = card("Valor") {
    manaCost = "{3}{W}"
    colorIdentity = "W"
    typeLine = "Creature — Incarnation"
    oracleText = "First strike\n" +
        "As long as this card is in your graveyard and you control a Plains, creatures you control have first strike."
    power = 2
    toughness = 2

    keywords(Keyword.FIRST_STRIKE)

    staticAbility {
        ability = GrantKeyword(Keyword.FIRST_STRIKE, GroupFilter(GameObjectFilter.Creature.youControl()))
        condition = Conditions.YouControl(Filters.PlainsCard)
        activeZones = setOf(Zone.GRAVEYARD)
    }

    metadata {
        rarity = Rarity.UNCOMMON
        collectorNumber = "32"
        artist = "Kev Walker"
        imageUri = "https://cards.scryfall.io/normal/front/5/8/58095f6b-d937-4871-b3af-5c6a1d9c04b3.jpg?1783945131"
    }
}
