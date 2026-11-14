package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.matchers.shouldBe

class SoulScourgeScenarioTest : ScenarioTestBase() {
    init {
        test("its enters and leaves abilities use the same targeted player") {
            val game = scenario()
                .withPlayers()
                .withCardInHand(1, "Soul Scourge")
                .withCardInHand(1, "Murder")
                .withLandsOnBattlefield(1, "Swamp", 8)
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            game.castSpell(1, "Soul Scourge")
            game.resolveStack()
            game.selectTargets(listOf(game.player2Id))
            game.resolveStack()

            game.getLifeTotal(2) shouldBe 17

            val scourge = game.findPermanent("Soul Scourge")!!
            game.castSpell(1, "Murder", targetId = scourge)
            game.resolveStack()

            game.getLifeTotal(2) shouldBe 20
            game.getLifeTotal(1) shouldBe 20
        }
    }
}
