package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class DeathWishScenarioTest : ScenarioTestBase() {

    init {
        test("fetches any sideboard card, loses half of life rounded up, and exiles Death Wish") {
            val game = scenario()
                .withPlayers("Player1", "Player2")
                .withCardInHand(1, "Death Wish")
                .withLandsOnBattlefield(1, "Swamp", 3)
                .withLifeTotal(1, 21)
                .withCardInSideboard(1, "Forest")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val cast = game.castSpell(1, "Death Wish")
            withClue("Death Wish should be castable: ${cast.error}") { cast.error shouldBe null }
            game.resolveStack()

            val decision = game.getPendingDecision() as? SelectCardsDecision
            decision.shouldNotBeNull()
            val forestId = decision.cardInfo!!.entries.single().key
            game.selectCards(listOf(forestId))

            game.isInHand(1, "Forest") shouldBe true
            game.isInSideboard(1, "Forest") shouldBe false
            withClue("Half of 21 rounds up to 11 life lost") {
                game.getLifeTotal(1) shouldBe 10
            }
            game.isInExile(1, "Death Wish") shouldBe true
            game.isInGraveyard(1, "Death Wish") shouldBe false
        }
    }
}
