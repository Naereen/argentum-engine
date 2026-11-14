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
 * Filth — Judgment #66
 * {3}{B} · Creature — Incarnation · 2/2
 * Swampwalk; from your graveyard, grants swampwalk to your creatures while you control a Swamp.
 */
val Filth = card("Filth") {
    manaCost = "{3}{B}"
    colorIdentity = "B"
    typeLine = "Creature — Incarnation"
    oracleText = "Swampwalk (This creature can't be blocked as long as defending player controls a Swamp.)\n" +
        "As long as this card is in your graveyard and you control a Swamp, creatures you control have swampwalk."
    power = 2
    toughness = 2

    keywords(Keyword.SWAMPWALK)

    staticAbility {
        ability = GrantKeyword(Keyword.SWAMPWALK, GroupFilter(GameObjectFilter.Creature.youControl()))
        condition = Conditions.YouControl(Filters.SwampCard)
        activeZones = setOf(Zone.GRAVEYARD)
    }

    metadata {
        rarity = Rarity.UNCOMMON
        collectorNumber = "66"
        artist = "Thomas M. Baxa"
        imageUri = "https://cards.scryfall.io/normal/front/3/7/37de06dc-c0c1-4edb-9732-2d16dbabfb31.jpg?1783945122"
    }
}
