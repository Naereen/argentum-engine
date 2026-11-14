package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class LlawanCephalidEmpressScenarioTest : ScenarioTestBase() {
    init {
        test("returns opposing blue creatures when it enters") {
            val game = scenario()
                .withPlayers()
                .withCardInHand(1, "Llawan, Cephalid Empress")
                .withLandsOnBattlefield(1, "Island", 4)
                .withCardOnBattlefield(1, "Wind Drake")
                .withCardOnBattlefield(2, "Wind Drake")
                .withCardOnBattlefield(2, "Grizzly Bears")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            game.castSpell(1, "Llawan, Cephalid Empress").error shouldBe null
            game.resolveStack()

            game.findPermanent(2, "Wind Drake") shouldBe null
            game.findPermanent(2, "Grizzly Bears") shouldNotBe null
            game.findPermanent(1, "Wind Drake") shouldNotBe null
            game.isInHand(2, "Wind Drake") shouldBe true
        }
    }
}
