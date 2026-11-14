package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.core.YesNoResponse
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class RebornHeroScenarioTest : ScenarioTestBase() {
    init {
        test("threshold lets it pay {W}{W} to return when it dies") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Reborn Hero")
                .withCardInHand(2, "Lightning Bolt")
                .withLandsOnBattlefield(2, "Mountain", 1)
                .withLandsOnBattlefield(1, "Plains", 2)
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .apply { repeat(7) { withCardInGraveyard(1, "Plains") } }
                .build()
            val hero = game.findPermanent("Reborn Hero")!!
            val bolt = game.findCardsInHand(2, "Lightning Bolt").single()

            game.execute(
                CastSpell(
                    playerId = game.player2Id,
                    cardId = bolt,
                    targets = listOf(ChosenTarget.Permanent(hero))
                )
            ).error shouldBe null
            game.resolveStack()
            val payment = game.getPendingDecision() as YesNoDecision
            game.submitDecision(YesNoResponse(payment.id, true)).error shouldBe null
            game.resolveStack()

            game.findPermanent("Reborn Hero") shouldNotBe null
        }
    }
}
