package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class GoldenWishScenarioTest : ScenarioTestBase() {

    init {
        test("fetches an artifact or enchantment, but not a creature, and exiles Golden Wish") {
            val game = scenario()
                .withPlayers("Player1", "Player2")
                .withCardInHand(1, "Golden Wish")
                .withLandsOnBattlefield(1, "Plains", 5)
                .withCardInSideboard(1, "Sol Ring")
                .withCardInSideboard(1, "Pacifism")
                .withCardInSideboard(1, "Grizzly Bears")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val cast = game.castSpell(1, "Golden Wish")
            withClue("Golden Wish should be castable: ${cast.error}") { cast.error shouldBe null }
            game.resolveStack()

            val decision = game.getPendingDecision() as? SelectCardsDecision
            decision.shouldNotBeNull()
            val offered = decision.cardInfo!!
            withClue("Artifacts and enchantments should be offered, but not creatures") {
                offered.values.any { it.name == "Sol Ring" } shouldBe true
                offered.values.any { it.name == "Pacifism" } shouldBe true
                offered.values.any { it.name == "Grizzly Bears" } shouldBe false
            }

            game.selectCards(listOf(offered.entries.first { it.value.name == "Sol Ring" }.key))

            game.isInHand(1, "Sol Ring") shouldBe true
            game.isInSideboard(1, "Sol Ring") shouldBe false
            game.isInSideboard(1, "Pacifism") shouldBe true
            game.isInExile(1, "Golden Wish") shouldBe true
            game.isInGraveyard(1, "Golden Wish") shouldBe false
        }
    }
}
