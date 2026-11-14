package com.wingedsheep.mtg.sets.definitions.jud.cards

import com.wingedsheep.sdk.dsl.Filters
import com.wingedsheep.sdk.dsl.Patterns
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity

/**
 * Cunning Wish {2}{U}
 * Instant
 *
 * You may reveal an instant card you own from outside the game and put it into your hand.
 * Exile Cunning Wish.
 */
val CunningWish = card("Cunning Wish") {
    manaCost = "{2}{U}"
    colorIdentity = "U"
    typeLine = "Instant"
    oracleText = "You may reveal an instant card you own from outside the game and put it into " +
        "your hand. Exile Cunning Wish."

    spell {
        selfExile()
        effect = Patterns.Sideboard.wish(Filters.Instant)
    }

    metadata {
        rarity = Rarity.RARE
        collectorNumber = "37"
        artist = "Jim Nelson"
        flavorText = "He wished for knowledge, but not for the will to apply it."
        imageUri = "https://cards.scryfall.io/normal/front/c/a/ca097675-5e82-493d-beab-9fc11efd7492.jpg?1783945130"
    }
}
