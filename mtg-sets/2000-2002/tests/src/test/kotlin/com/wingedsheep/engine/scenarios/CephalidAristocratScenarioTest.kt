package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import io.kotest.matchers.shouldBe

class CephalidAristocratScenarioTest : ScenarioTestBase() {
    init {
        test("becoming a spell's target mills two cards") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Cephalid Aristocrat")
                .withCardInHand(2, "Giant Growth")
                .withLandsOnBattlefield(2, "Forest", 1)
                .apply { repeat(3) { withCardInLibrary(1, "Island") } }
                .withActivePlayer(2)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()
            val aristocrat = game.findPermanent("Cephalid Aristocrat")!!
            val growth = game.findCardsInHand(2, "Giant Growth").single()

            game.execute(
                CastSpell(
                    playerId = game.player2Id,
                    cardId = growth,
                    targets = listOf(ChosenTarget.Permanent(aristocrat))
                )
            ).error shouldBe null
            game.resolveStack()

            game.state.getZone(ZoneKey(game.player1Id, Zone.GRAVEYARD)).size shouldBe 2
        }
    }
}
