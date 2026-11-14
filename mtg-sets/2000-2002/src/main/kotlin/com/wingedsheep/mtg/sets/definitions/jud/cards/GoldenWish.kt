package com.wingedsheep.mtg.sets.definitions.jud.cards

import com.wingedsheep.sdk.dsl.Patterns
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.GameObjectFilter

/**
 * Golden Wish {3}{W}{W}
 * Sorcery
 *
 * You may reveal an artifact or enchantment card you own from outside the game and put it into
 * your hand. Exile Golden Wish.
 */
val GoldenWish = card("Golden Wish") {
    manaCost = "{3}{W}{W}"
    colorIdentity = "W"
    typeLine = "Sorcery"
    oracleText = "You may reveal an artifact or enchantment card you own from outside the game " +
        "and put it into your hand. Exile Golden Wish."

    spell {
        selfExile()
        effect = Patterns.Sideboard.wish(GameObjectFilter.Artifact or GameObjectFilter.Enchantment)
    }

    metadata {
        rarity = Rarity.RARE
        collectorNumber = "12"
        artist = "Alan Pollack"
        flavorText = "She wished for nobility, but not for a nation to honor it."
        imageUri = "https://cards.scryfall.io/normal/front/d/c/dc409ded-41f3-4f14-8199-72a9fe98bac0.jpg?1783945136"
    }
}
