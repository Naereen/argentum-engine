package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.mtg.sets.definitions.tor.cards.MajorTeroh
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class MajorTerohScenarioTest : ScenarioTestBase() {
    init {
        test("sacrificing it exiles all black creatures") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Major Teroh")
                .withLandsOnBattlefield(1, "Plains", 5)
                .withCardOnBattlefield(1, "Putrid Imp")
                .withCardOnBattlefield(2, "Putrid Imp")
                .withCardOnBattlefield(2, "Grizzly Bears")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()
            val teroh = game.findPermanent("Major Teroh")!!

            game.execute(
                ActivateAbility(
                    playerId = game.player1Id,
                    sourceId = teroh,
                    abilityId = MajorTeroh.activatedAbilities.single().id
                )
            ).error shouldBe null
            game.resolveStack()

            game.isInExile(1, "Putrid Imp") shouldBe true
            game.isInExile(2, "Putrid Imp") shouldBe true
            game.findPermanent(2, "Grizzly Bears") shouldNotBe null
        }
    }
}
