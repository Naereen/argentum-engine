package com.wingedsheep.mtg.sets.definitions.tor.cards

import com.wingedsheep.sdk.dsl.Patterns
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity

val CephalidAristocrat = card("Cephalid Aristocrat") {
    manaCost = "{4}{U}"
    colorIdentity = "U"
    typeLine = "Creature — Octopus Noble"
    power = 3
    toughness = 3
    oracleText = "Whenever this creature becomes the target of a spell or ability, mill two cards."

    triggeredAbility {
        trigger = Triggers.self.becomesTarget()
        effect = Patterns.Library.mill(2)
    }

    metadata {
        rarity = Rarity.COMMON
        collectorNumber = "27"
        artist = "Rob Alexander"
        imageUri = "https://cards.scryfall.io/normal/front/b/d/bd9ca63d-7e77-48f3-abdc-2d2f9cb3a0d9.jpg?1783945165"
    }
}
