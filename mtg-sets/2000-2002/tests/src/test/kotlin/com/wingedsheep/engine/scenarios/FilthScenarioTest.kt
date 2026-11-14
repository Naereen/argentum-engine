package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.matchers.shouldBe

class FilthScenarioTest : ScenarioTestBase() {

    init {
        test("Filth grants swampwalk from the graveyard only while its owner controls a Swamp") {
            val game = scenario()
                .withPlayers()
                .withCardInGraveyard(1, "Filth")
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withLandsOnBattlefield(1, "Swamp", 1)
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()
            val bear = game.findPermanent("Grizzly Bears")!!

            game.state.projectedState.hasKeyword(bear, Keyword.SWAMPWALK) shouldBe true

            val withoutSwamp = scenario()
                .withPlayers()
                .withCardInGraveyard(1, "Filth")
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()
            val otherBear = withoutSwamp.findPermanent("Grizzly Bears")!!
            withoutSwamp.state.projectedState.hasKeyword(otherBear, Keyword.SWAMPWALK) shouldBe false
        }
    }
}
