package com.wingedsheep.mtg.sets.definitions.tor.cards

import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.filters.unified.GroupFilter
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import com.wingedsheep.sdk.scripting.PlayersCantCastSpells
import com.wingedsheep.sdk.scripting.references.Player

val LlawanCephalidEmpress = card("Llawan, Cephalid Empress") {
    manaCost = "{3}{U}"
    colorIdentity = "U"
    typeLine = "Legendary Creature — Octopus Noble"
    power = 2
    toughness = 3
    oracleText = "When this creature enters, return all blue creatures your opponents control to their owners' hands.\n" +
        "Your opponents can't cast blue creature spells."

    triggeredAbility {
        trigger = Triggers.self.enters()
        effect = Effects.ForEachInGroup(
            filter = GroupFilter(GameObjectFilter.Creature.withColor(Color.BLUE).opponentControls()),
            effect = Effects.ReturnToHand(EffectTarget.IterationEntity)
        )
    }

    staticAbility {
        ability = PlayersCantCastSpells(
            affected = Player.EachOpponent,
            spellFilter = GameObjectFilter.Creature.withColor(Color.BLUE)
        )
    }

    metadata {
        rarity = Rarity.RARE
        collectorNumber = "42"
        artist = "Mark Zug"
        imageUri = "https://cards.scryfall.io/normal/front/a/9/a9821970-a5da-4045-93d8-f58c9e5797c1.jpg?1783945161"
    }
}
