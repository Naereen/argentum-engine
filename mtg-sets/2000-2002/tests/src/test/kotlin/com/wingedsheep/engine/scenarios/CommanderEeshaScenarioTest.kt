package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.matchers.shouldBe

class CommanderEeshaScenarioTest : ScenarioTestBase() {

    init {
        test("Commander Eesha is a 2/4 flier with protection from creatures") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Commander Eesha")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()
            val eesha = game.findPermanent("Commander Eesha")!!
            val projected = game.state.projectedState

            projected.getPower(eesha) shouldBe 2
            projected.getToughness(eesha) shouldBe 4
            projected.hasKeyword(eesha, Keyword.FLYING) shouldBe true
            projected.hasKeyword(eesha, "PROTECTION_FROM_CARDTYPE_CREATURE") shouldBe true
        }
    }
}
