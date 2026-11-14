package com.wingedsheep.mtg.sets.definitions.tor.cards

import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter

val Liquify = card("Liquify") {
    manaCost = "{2}{U}"
    colorIdentity = "U"
    typeLine = "Instant"
    oracleText = "Counter target spell with mana value 3 or less. If that spell is countered this way, exile it instead of putting it into its owner's graveyard."

    spell {
        target(TargetFilter.SpellOnStack.manaValueAtMost(3))
        effect = Effects.CounterSpellToExile()
    }

    metadata {
        rarity = Rarity.COMMON
        collectorNumber = "41"
        artist = "Ron Spencer"
        imageUri = "https://cards.scryfall.io/normal/front/1/2/12fadf25-0995-440d-a3e6-7964ed86cff6.jpg?1783945162"
    }
}
