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
 * Brawn — Judgment #107
 * {3}{G} · Creature — Incarnation · 3/3
 * Trample; from your graveyard, grants trample to your creatures while you control a Forest.
 */
val Brawn = card("Brawn") {
    manaCost = "{3}{G}"
    colorIdentity = "G"
    typeLine = "Creature — Incarnation"
    oracleText = "Trample\n" +
        "As long as this card is in your graveyard and you control a Forest, creatures you control have trample."
    power = 3
    toughness = 3

    keywords(Keyword.TRAMPLE)

    staticAbility {
        ability = GrantKeyword(Keyword.TRAMPLE, GroupFilter(GameObjectFilter.Creature.youControl()))
        condition = Conditions.YouControl(Filters.ForestCard)
        activeZones = setOf(Zone.GRAVEYARD)
    }

    metadata {
        rarity = Rarity.UNCOMMON
        collectorNumber = "107"
        artist = "Matt Cavotta"
        imageUri = "https://cards.scryfall.io/normal/front/e/9/e9f4b7fc-8793-43af-ade3-b23846a80457.jpg?1783945114"
    }
}
