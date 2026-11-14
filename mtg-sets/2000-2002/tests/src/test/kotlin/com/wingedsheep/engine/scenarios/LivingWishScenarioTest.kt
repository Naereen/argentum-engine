package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class LivingWishScenarioTest : ScenarioTestBase() {

    init {
        test("fetches a creature or land, but not an instant, and exiles Living Wish") {
            val game = scenario()
                .withPlayers("Player1", "Player2")
                .withCardInHand(1, "Living Wish")
                .withLandsOnBattlefield(1, "Forest", 2)
                .withCardInSideboard(1, "Grizzly Bears")
                .withCardInSideboard(1, "Forest")
                .withCardInSideboard(1, "Fact or Fiction")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val cast = game.castSpell(1, "Living Wish")
            withClue("Living Wish should be castable: ${cast.error}") { cast.error shouldBe null }
            game.resolveStack()

            val decision = game.getPendingDecision() as? SelectCardsDecision
            decision.shouldNotBeNull()
            val offered = decision.cardInfo!!
            withClue("Creatures and lands should be offered, but not instants") {
                offered.values.any { it.name == "Grizzly Bears" } shouldBe true
                offered.values.any { it.name == "Forest" } shouldBe true
                offered.values.any { it.name == "Fact or Fiction" } shouldBe false
            }

            game.selectCards(listOf(offered.entries.first { it.value.name == "Grizzly Bears" }.key))

            game.isInHand(1, "Grizzly Bears") shouldBe true
            game.isInSideboard(1, "Grizzly Bears") shouldBe false
            game.isInSideboard(1, "Forest") shouldBe true
            game.isInSideboard(1, "Fact or Fiction") shouldBe true
            game.isInExile(1, "Living Wish") shouldBe true
            game.isInGraveyard(1, "Living Wish") shouldBe false
        }
    }
}
