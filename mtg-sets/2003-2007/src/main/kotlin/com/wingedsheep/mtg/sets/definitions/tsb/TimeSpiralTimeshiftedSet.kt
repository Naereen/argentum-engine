package com.wingedsheep.mtg.sets.definitions.tsb

import com.wingedsheep.mtg.sets.discovery.CardDiscovery
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.MtgSet
import com.wingedsheep.sdk.model.Printing

object TimeSpiralTimeshiftedSet : MtgSet {
    override val code = "TSB"
    override val displayName = "Time Spiral Timeshifted"
    override val releaseDate = "2006-10-06"
    override val block = "Time Spiral"
    override val extensionSet = true
    override val sealedSupported = false
    override val incomplete = true

    override val cards: List<CardDefinition> by lazy {
        CardDiscovery.findIn(CARDS_PACKAGE)
    }

    override val printings: List<Printing> by lazy {
        CardDiscovery.findPrintingsIn(CARDS_PACKAGE)
    }

    private const val CARDS_PACKAGE = "com.wingedsheep.mtg.sets.definitions.tsb.cards"
}
