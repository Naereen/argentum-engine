package com.wingedsheep.mtg.sets.definitions.jud.cards

import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity

/**
 * Mirror Wall — Judgment #47
 * {3}{U} · Creature — Wall · 3/4
 * Defender; {W}: may attack this turn as though it didn't have defender.
 */
val MirrorWall = card("Mirror Wall") {
    manaCost = "{3}{U}"
    colorIdentity = "U"
    typeLine = "Creature — Wall"
    oracleText = "Defender (This creature can't attack.)\n" +
        "{W}: This creature can attack this turn as though it didn't have defender."
    power = 3
    toughness = 4

    keywords(Keyword.DEFENDER)

    activatedAbility {
        cost = Costs.Mana("{W}")
        effect = Effects.CanAttackDespiteDefenderThisTurn()
    }

    metadata {
        rarity = Rarity.COMMON
        collectorNumber = "47"
        artist = "Mark Brill"
        imageUri = "https://cards.scryfall.io/normal/front/d/0/d0e52b73-ebdf-4339-8780-84327a59ca57.jpg?1783945127"
    }
}
