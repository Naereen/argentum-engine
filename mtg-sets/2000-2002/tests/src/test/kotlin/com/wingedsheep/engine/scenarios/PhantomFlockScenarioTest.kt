package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.state.components.battlefield.CountersComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.CounterType
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.matchers.shouldBe

class PhantomFlockScenarioTest : ScenarioTestBase() {

    init {
        test("enters as a 3/3 flier and prevents damage by removing one counter") {
            val game = scenario()
                .withPlayers()
                .withLandsOnBattlefield(1, "Plains", 2)
                .withLandsOnBattlefield(1, "Mountain", 4)
                .withCardInHand(1, "Phantom Flock")
                .withCardInHand(1, "Lightning Bolt")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()
            val flockCard = game.findCardsInHand(1, "Phantom Flock").single()

            game.execute(CastSpell(game.player1Id, flockCard)).error shouldBe null
            game.resolveStack()
            val flock = game.findPermanent("Phantom Flock")!!
            val counters = game.state.getEntity(flock)!!.get<CountersComponent>()!!
            counters.getCount(CounterType.PLUS_ONE_PLUS_ONE) shouldBe 3
            game.state.projectedState.getPower(flock) shouldBe 3
            game.state.projectedState.getToughness(flock) shouldBe 3
            game.state.projectedState.hasKeyword(flock, Keyword.FLYING) shouldBe true

            val bolt = game.findCardsInHand(1, "Lightning Bolt").single()
            game.execute(
                CastSpell(
                    playerId = game.player1Id,
                    cardId = bolt,
                    targets = listOf(ChosenTarget.Permanent(flock)),
                ),
            ).error shouldBe null
            game.resolveStack()

            game.findPermanent("Phantom Flock") shouldBe flock
            val remainingCounters = game.state.getEntity(flock)!!.get<CountersComponent>()!!
            remainingCounters.getCount(CounterType.PLUS_ONE_PLUS_ONE) shouldBe 2
            game.state.projectedState.getPower(flock) shouldBe 2
            game.state.projectedState.getToughness(flock) shouldBe 2
        }
    }
}
