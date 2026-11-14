package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.mtg.sets.definitions.jud.cards.AnuridBarkripper
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.matchers.shouldBe

class AnuridBarkripperScenarioTest : ScenarioTestBase() {

    init {
        test("threshold changes Anurid Barkripper from 2/2 to 4/4 at seven graveyard cards") {
            fun game(graveyardCards: Int) = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Anurid Barkripper")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .apply { repeat(graveyardCards) { withCardInGraveyard(1, "Forest") } }
                .build()

            val belowThreshold = game(6)
            val barkripperBelow = belowThreshold.findPermanent("Anurid Barkripper")!!
            belowThreshold.state.projectedState.getPower(barkripperBelow) shouldBe 2
            belowThreshold.state.projectedState.getToughness(barkripperBelow) shouldBe 2

            val atThreshold = game(7)
            val barkripperAt = atThreshold.findPermanent("Anurid Barkripper")!!
            atThreshold.state.projectedState.getPower(barkripperAt) shouldBe 4
            atThreshold.state.projectedState.getToughness(barkripperAt) shouldBe 4
        }
    }
}
