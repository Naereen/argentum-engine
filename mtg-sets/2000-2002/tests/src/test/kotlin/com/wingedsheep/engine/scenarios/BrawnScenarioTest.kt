package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.mtg.sets.definitions.jud.cards.Brawn
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.matchers.shouldBe

class BrawnScenarioTest : ScenarioTestBase() {

    init {
        test("Brawn grants trample from the graveyard only while its owner controls a Forest") {
            val game = scenario()
                .withPlayers()
                .withCardInGraveyard(1, "Brawn")
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withCardOnBattlefield(2, "Grizzly Bears")
                .withLandsOnBattlefield(1, "Forest", 1)
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()
            val myBear = game.findPermanents("Grizzly Bears").first()
            val opponentBear = game.findPermanents("Grizzly Bears").last()

            game.state.projectedState.hasKeyword(myBear, Keyword.TRAMPLE) shouldBe true
            game.state.projectedState.hasKeyword(opponentBear, Keyword.TRAMPLE) shouldBe false

            val withoutForest = scenario()
                .withPlayers()
                .withCardInGraveyard(1, "Brawn")
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()
            val bear = withoutForest.findPermanent("Grizzly Bears")!!
            withoutForest.state.projectedState.hasKeyword(bear, Keyword.TRAMPLE) shouldBe false
        }
    }
}
