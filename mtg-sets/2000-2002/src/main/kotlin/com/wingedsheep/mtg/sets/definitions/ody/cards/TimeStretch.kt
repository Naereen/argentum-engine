package com.wingedsheep.mtg.sets.definitions.ody.cards

import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Targets
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity

val TimeStretch = card("Time Stretch") {
    manaCost = "{8}{U}{U}"
    colorIdentity = "U"
    typeLine = "Sorcery"
    oracleText = "Target player takes two extra turns after this one."

    spell {
        val player = target(Targets.Player)
        effect = Effects.TakeExtraTurn(player) then Effects.TakeExtraTurn(player)
    }

    metadata {
        rarity = Rarity.RARE
        collectorNumber = "108"
        artist = "Paolo Parente"
        imageUri = "https://cards.scryfall.io/normal/front/d/4/d4c3d626-880c-422a-ac09-a79a52847477.jpg?1783945253"
    }
}
