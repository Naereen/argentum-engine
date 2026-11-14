package com.wingedsheep.mtg.sets.definitions.jud.cards

import com.wingedsheep.sdk.dsl.Conditions
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.ConditionalStaticAbility
import com.wingedsheep.sdk.scripting.GrantTriggeredAbility
import com.wingedsheep.sdk.scripting.ModifyStats
import com.wingedsheep.sdk.scripting.TriggeredAbility
import com.wingedsheep.sdk.scripting.filters.unified.GroupFilter
import com.wingedsheep.sdk.scripting.targets.EffectTarget

/**
 * Treacherous Werewolf — Judgment #76
 * {2}{B} · Creature — Werewolf Minion · 2/2
 * Threshold grants +2/+2 and a dies trigger that makes you lose 4 life.
 */
val TreacherousWerewolf = card("Treacherous Werewolf") {
    manaCost = "{2}{B}"
    colorIdentity = "B"
    typeLine = "Creature — Werewolf Minion"
    oracleText = "Threshold — As long as there are seven or more cards in your graveyard, this creature gets +2/+2 " +
        "and has \"When this creature dies, you lose 4 life.\""
    power = 2
    toughness = 2

    staticAbility {
        ability = ConditionalStaticAbility(
            ability = ModifyStats(powerBonus = 2, toughnessBonus = 2, filter = GroupFilter.source()),
            condition = Conditions.CardsInGraveyardAtLeast(7),
        )
    }
    staticAbility {
        ability = ConditionalStaticAbility(
            ability = GrantTriggeredAbility(
                ability = TriggeredAbility.create(
                    trigger = Triggers.self.dies(),
                    effect = Effects.LoseLife(4, EffectTarget.Controller),
                ),
                filter = GroupFilter.source(),
            ),
            condition = Conditions.CardsInGraveyardAtLeast(7),
        )
    }

    metadata {
        rarity = Rarity.COMMON
        collectorNumber = "76"
        artist = "Mark Tedin"
        imageUri = "https://cards.scryfall.io/normal/front/c/9/c9647726-302b-4fc4-91d7-2aa0bba0b653.jpg?1783945121"
    }
}
