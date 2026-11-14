package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class FacelessButcherScenarioTest : ScenarioTestBase() {
    init {
        test("its enters ability exiles another target creature") {
            val game = scenario()
                .withPlayers()
                .withCardInHand(1, "Faceless Butcher")
                .withCardInHand(1, "Murder")
                .withLandsOnBattlefield(1, "Swamp", 7)
                .withCardOnBattlefield(2, "Grizzly Bears")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            game.castSpell(1, "Faceless Butcher").error shouldBe null
            game.resolveStack()
            game.selectTargets(listOf(game.findPermanent("Grizzly Bears")!!))
            game.resolveStack()

            game.isInExile(2, "Grizzly Bears") shouldBe true
            val butcher = game.findPermanent("Faceless Butcher")!!
            game.castSpell(1, "Murder", targetId = butcher).error shouldBe null
            game.resolveStack()
            game.findPermanent(2, "Grizzly Bears") shouldNotBe null
        }
    }
}
