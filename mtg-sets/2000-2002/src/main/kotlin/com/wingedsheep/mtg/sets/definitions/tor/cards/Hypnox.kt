package com.wingedsheep.mtg.sets.definitions.tor.cards

import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Conditions
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Targets
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.effects.CardDestination
import com.wingedsheep.sdk.scripting.effects.CardSource
import com.wingedsheep.sdk.scripting.effects.CompositeEffect
import com.wingedsheep.sdk.scripting.effects.GatherCardsEffect
import com.wingedsheep.sdk.scripting.effects.MoveCollectionEffect
import com.wingedsheep.sdk.scripting.references.Player

val Hypnox = card("Hypnox") {
    manaCost = "{8}{B}{B}{B}"
    colorIdentity = "B"
    typeLine = "Creature — Nightmare Horror"
    power = 8
    toughness = 8
    oracleText = "Flying\n" +
        "When this creature enters, if you cast it from your hand, exile all cards from target opponent's hand.\n" +
        "When this creature leaves the battlefield, return the exiled cards to their owners' hands."

    keywords(com.wingedsheep.sdk.core.Keyword.FLYING)

    triggeredAbility {
        trigger = Triggers.self.enters()
        interveningIf = Conditions.TriggeringEntityWasCastFrom(Zone.HAND)
        target(Targets.Opponent)
        effect = CompositeEffect(
            listOf(
                GatherCardsEffect(
                    source = CardSource.FromZone(Zone.HAND, Player.TargetOpponent),
                    storeAs = "hypnoxExiledHand"
                ),
                MoveCollectionEffect(
                    from = "hypnoxExiledHand",
                    destination = CardDestination.ToZone(Zone.EXILE, Player.TargetOpponent),
                    linkToSource = true
                )
            )
        )
    }

    triggeredAbility {
        trigger = Triggers.self.leaves()
        effect = Effects.ReturnLinkedExileToHand()
    }

    metadata {
        rarity = Rarity.RARE
        collectorNumber = "64"
        artist = "Greg Staples"
        imageUri = "https://cards.scryfall.io/normal/front/0/e/0e0e53a1-11d5-4975-b698-8b5d72d241f0.jpg?1783945156"
    }
}
