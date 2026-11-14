package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class CunningWishScenarioTest : ScenarioTestBase() {

    init {
        test("fetches an instant from the sideboard and exiles Cunning Wish") {
            val game = scenario()
                .withPlayers("Player1", "Player2")
                .withCardInHand(1, "Cunning Wish")
                .withLandsOnBattlefield(1, "Island", 3)
                .withCardInSideboard(1, "Fact or Fiction")
                .withCardInSideboard(1, "Swelter")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val cast = game.castSpell(1, "Cunning Wish")
            withClue("Cunning Wish should be castable: ${cast.error}") { cast.error shouldBe null }
            game.resolveStack()

            val decision = game.getPendingDecision() as? SelectCardsDecision
            decision.shouldNotBeNull()
            val offered = decision.cardInfo!!
            withClue("Only the instant should be offered") {
                offered.values.any { it.name == "Fact or Fiction" } shouldBe true
                offered.values.any { it.name == "Swelter" } shouldBe false
            }

            game.selectCards(listOf(offered.entries.first { it.value.name == "Fact or Fiction" }.key))

            game.isInHand(1, "Fact or Fiction") shouldBe true
            game.isInSideboard(1, "Fact or Fiction") shouldBe false
            game.isInSideboard(1, "Swelter") shouldBe true
            game.isInExile(1, "Cunning Wish") shouldBe true
            game.isInGraveyard(1, "Cunning Wish") shouldBe false
        }
    }
}
