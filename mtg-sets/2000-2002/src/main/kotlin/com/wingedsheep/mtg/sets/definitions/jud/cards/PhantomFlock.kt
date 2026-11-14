package com.wingedsheep.mtg.sets.definitions.jud.cards

import com.wingedsheep.sdk.core.CounterType
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.EntersWithCounters
import com.wingedsheep.sdk.scripting.PreventDamageByRemovingCounter

/**
 * Phantom Flock — Judgment #16
 * {3}{W}{W} · Creature — Bird Soldier Spirit · 0/0
 * Flying; enters with three +1/+1 counters. Damage to it is prevented and removes a +1/+1 counter.
 */
val PhantomFlock = card("Phantom Flock") {
    manaCost = "{3}{W}{W}"
    colorIdentity = "W"
    typeLine = "Creature — Bird Soldier Spirit"
    oracleText = "Flying\n" +
        "This creature enters with three +1/+1 counters on it.\n" +
        "If damage would be dealt to this creature, prevent that damage. Remove a +1/+1 counter from this creature."
    power = 0
    toughness = 0

    keywords(Keyword.FLYING)
    replacementEffect(
        EntersWithCounters(
            counterType = CounterType.PLUS_ONE_PLUS_ONE,
            count = 3,
            selfOnly = true,
        ),
    )
    replacementEffect(PreventDamageByRemovingCounter())

    metadata {
        rarity = Rarity.UNCOMMON
        collectorNumber = "16"
        artist = "David Martin"
        imageUri = "https://cards.scryfall.io/normal/front/6/1/617d4246-c827-4742-ab6a-c5170c12cb87.jpg?1783945135"
    }
}
