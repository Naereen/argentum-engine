package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.matchers.shouldBe

class ValorScenarioTest : ScenarioTestBase() {

    init {
        test("Valor grants first strike from the graveyard only while its owner controls a Plains") {
            val game = scenario()
                .withPlayers()
                .withCardInGraveyard(1, "Valor")
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withCardOnBattlefield(2, "Grizzly Bears")
                .withLandsOnBattlefield(1, "Plains", 1)
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()
            val bears = game.findPermanents("Grizzly Bears")

            game.state.projectedState.hasKeyword(bears.first(), Keyword.FIRST_STRIKE) shouldBe true
            game.state.projectedState.hasKeyword(bears.last(), Keyword.FIRST_STRIKE) shouldBe false

            val withoutPlains = scenario()
                .withPlayers()
                .withCardInGraveyard(1, "Valor")
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()
            val bear = withoutPlains.findPermanent("Grizzly Bears")!!
            withoutPlains.state.projectedState.hasKeyword(bear, Keyword.FIRST_STRIKE) shouldBe false
        }
    }
}
