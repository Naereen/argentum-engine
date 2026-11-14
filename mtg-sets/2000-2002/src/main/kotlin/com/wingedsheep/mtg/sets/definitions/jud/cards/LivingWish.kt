package com.wingedsheep.mtg.sets.definitions.jud.cards

import com.wingedsheep.sdk.dsl.Filters
import com.wingedsheep.sdk.dsl.Patterns
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity

/**
 * Living Wish {1}{G}
 * Sorcery
 *
 * You may reveal a creature or land card you own from outside the game and put it into your hand.
 * Exile Living Wish.
 */
val LivingWish = card("Living Wish") {
    manaCost = "{1}{G}"
    colorIdentity = "G"
    typeLine = "Sorcery"
    oracleText = "You may reveal a creature or land card you own from outside the game and put it " +
        "into your hand. Exile Living Wish."

    spell {
        selfExile()
        effect = Patterns.Sideboard.wish(Filters.Creature or Filters.Land)
    }

    metadata {
        rarity = Rarity.RARE
        collectorNumber = "124"
        artist = "Eric Peterson"
        flavorText = "He wished for growth, but not for a way to control it."
        imageUri = "https://cards.scryfall.io/normal/front/2/4/2478a8d2-ca44-4c42-8d75-dd9cb1b59f61.jpg?1783945110"
    }
}
