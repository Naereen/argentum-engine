package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.matchers.shouldBe

class LiquifyScenarioTest : ScenarioTestBase() {
    init {
        test("counters a low-mana-value spell and exiles it") {
            val game = scenario()
                .withPlayers()
                .withCardInHand(1, "Liquify")
                .withLandsOnBattlefield(1, "Island", 3)
                .withCardInHand(2, "Grizzly Bears")
                .withLandsOnBattlefield(2, "Forest", 2)
                .withActivePlayer(2)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            game.castSpell(2, "Grizzly Bears")
            game.castSpellTargetingStackSpell(1, "Liquify", "Grizzly Bears")
            game.resolveStack()

            game.isInExile(2, "Grizzly Bears") shouldBe true
        }
    }
}
