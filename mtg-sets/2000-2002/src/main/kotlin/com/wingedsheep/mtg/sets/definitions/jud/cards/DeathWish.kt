package com.wingedsheep.mtg.sets.definitions.jud.cards

import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Patterns
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity

/**
 * Death Wish {1}{B}{B}
 * Sorcery
 *
 * You may put a card you own from outside the game into your hand. You lose half your life,
 * rounded up. Exile Death Wish.
 */
val DeathWish = card("Death Wish") {
    manaCost = "{1}{B}{B}"
    colorIdentity = "B"
    typeLine = "Sorcery"
    oracleText = "You may put a card you own from outside the game into your hand. You lose half " +
        "your life, rounded up. Exile Death Wish."

    spell {
        selfExile()
        effect = Patterns.Sideboard.wish(revealed = false) then Effects.LoseHalfLife()
    }

    metadata {
        rarity = Rarity.RARE
        collectorNumber = "64"
        artist = "Jeff Easley"
        flavorText = "He wished for power, but not for the longevity to abuse it."
        imageUri = "https://cards.scryfall.io/normal/front/7/b/7bf134c9-a50d-4eff-a5a8-7cfe6a010080.jpg?1783945123"
    }
}
