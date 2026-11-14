package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.matchers.shouldBe

class TreacherousWerewolfScenarioTest : ScenarioTestBase() {

    init {
        test("threshold gives +2/+2 and makes its death cost four life") {
            val game = scenario()
                .withPlayers()
                .withLandsOnBattlefield(1, "Swamp", 3)
                .withCardOnBattlefield(1, "Treacherous Werewolf")
                .withCardInHand(1, "Murder")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .apply { repeat(7) { withCardInGraveyard(1, "Island") } }
                .build()
            val wolf = game.findPermanent("Treacherous Werewolf")!!
            val murder = game.findCardsInHand(1, "Murder").single()

            game.state.projectedState.getPower(wolf) shouldBe 4
            game.state.projectedState.getToughness(wolf) shouldBe 4
            game.execute(
                CastSpell(
                    playerId = game.player1Id,
                    cardId = murder,
                    targets = listOf(ChosenTarget.Permanent(wolf)),
                ),
            ).error shouldBe null
            game.resolveStack()

            game.findPermanent("Treacherous Werewolf") shouldBe null
            game.getLifeTotal(1) shouldBe 16
        }

        test("without threshold, dying does not cause life loss even though it brings the graveyard to seven") {
            val game = scenario()
                .withPlayers()
                .withLandsOnBattlefield(1, "Swamp", 3)
                .withCardOnBattlefield(1, "Treacherous Werewolf")
                .withCardInHand(1, "Murder")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .apply { repeat(6) { withCardInGraveyard(1, "Island") } }
                .build()
            val wolf = game.findPermanent("Treacherous Werewolf")!!
            val murder = game.findCardsInHand(1, "Murder").single()

            game.state.projectedState.getPower(wolf) shouldBe 2
            game.execute(
                CastSpell(
                    playerId = game.player1Id,
                    cardId = murder,
                    targets = listOf(ChosenTarget.Permanent(wolf)),
                ),
            ).error shouldBe null
            game.resolveStack()

            game.getLifeTotal(1) shouldBe 20
        }
    }
}
