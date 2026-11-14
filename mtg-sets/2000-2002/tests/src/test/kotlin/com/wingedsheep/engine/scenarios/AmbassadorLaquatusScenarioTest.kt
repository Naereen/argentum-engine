package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.mtg.sets.definitions.tor.cards.AmbassadorLaquatus
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import io.kotest.matchers.shouldBe

class AmbassadorLaquatusScenarioTest : ScenarioTestBase() {
    init {
        test("activating its ability mills the targeted player for three") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Ambassador Laquatus")
                .withLandsOnBattlefield(1, "Island", 3)
                .apply { repeat(3) { withCardInLibrary(2, "Forest") } }
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()
            val source = game.findPermanent("Ambassador Laquatus")!!

            game.execute(
                ActivateAbility(
                    playerId = game.player1Id,
                    sourceId = source,
                    abilityId = AmbassadorLaquatus.activatedAbilities.single().id,
                    targets = listOf(ChosenTarget.Player(game.player2Id))
                )
            ).error shouldBe null
            game.resolveStack()

            game.state.getZone(ZoneKey(game.player2Id, Zone.GRAVEYARD)).size shouldBe 3
        }
    }
}
