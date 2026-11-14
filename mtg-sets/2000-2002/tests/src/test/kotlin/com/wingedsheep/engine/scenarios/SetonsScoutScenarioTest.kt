package com.wingedsheep.engine.scenarios

import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.engine.support.ScenarioTestBase
import io.kotest.matchers.shouldBe

class SetonsScoutScenarioTest : ScenarioTestBase() {
    init {
        test("has reach and gets +2/+2 at threshold") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Seton's Scout")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .apply { repeat(7) { withCardInGraveyard(1, "Forest") } }
                .build()
            val scout = game.findPermanent("Seton's Scout")!!

            game.state.projectedState.getPower(scout) shouldBe 4
            game.state.projectedState.getToughness(scout) shouldBe 3
            game.state.projectedState.hasKeyword(scout, Keyword.REACH) shouldBe true
        }
    }
}
