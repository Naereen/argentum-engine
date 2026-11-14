package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.matchers.shouldBe

class HypnoxScenarioTest : ScenarioTestBase() {
    init {
        test("cast from hand exiles all cards in a target opponent's hand") {
            val game = scenario()
                .withPlayers()
                .withCardInHand(1, "Hypnox")
                .withCardInHand(1, "Murder")
                .withLandsOnBattlefield(1, "Swamp", 14)
                .withCardInHand(2, "Forest")
                .withCardInHand(2, "Grizzly Bears")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            game.castSpell(1, "Hypnox")
            game.resolveStack()
            game.selectTargets(listOf(game.player2Id))
            game.resolveStack()

            game.handSize(2) shouldBe 0
            game.isInExile(2, "Forest") shouldBe true
            game.isInExile(2, "Grizzly Bears") shouldBe true

            val hypnox = game.findPermanent("Hypnox")!!
            game.castSpell(1, "Murder", targetId = hypnox)
            game.resolveStack()
            game.handSize(2) shouldBe 2
            game.isInExile(2, "Forest") shouldBe false
        }
    }
}
