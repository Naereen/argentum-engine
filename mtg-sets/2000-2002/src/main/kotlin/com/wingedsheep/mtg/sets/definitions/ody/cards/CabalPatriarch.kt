package com.wingedsheep.mtg.sets.definitions.ody.cards

import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter

val CabalPatriarch = card("Cabal Patriarch") {
    manaCost = "{3}{B}{B}{B}"
    colorIdentity = "B"
    typeLine = "Legendary Creature — Human Wizard"
    power = 5
    toughness = 5
    oracleText = "{2}{B}, Sacrifice a creature: Target creature gets -2/-2 until end of turn.\n" +
        "{2}{B}, Exile a creature card from your graveyard: Target creature gets -2/-2 until end of turn."

    activatedAbility {
        cost = Costs.Composite(Costs.Mana("{2}{B}"), Costs.SacrificePermanents(GameObjectFilter.Creature))
        val creature = target(TargetFilter.Creature)
        effect = Effects.ModifyStats(-2, -2, creature)
    }

    activatedAbility {
        cost = Costs.Composite(Costs.Mana("{2}{B}"), Costs.ExileFromGraveyard(1, GameObjectFilter.Creature))
        val creature = target(TargetFilter.Creature)
        effect = Effects.ModifyStats(-2, -2, creature)
    }

    metadata {
        rarity = Rarity.RARE
        collectorNumber = "120"
        artist = "Mark Zug"
        imageUri = "https://cards.scryfall.io/normal/front/0/d/0d2f0da3-a13e-4a45-98dc-6227cf952a5e.jpg?1783945251"
    }
}
