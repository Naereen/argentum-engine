package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.mtg.sets.definitions.tor.cards.PossessedAven
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.scripting.ConditionalStaticAbility
import com.wingedsheep.sdk.scripting.GrantActivatedAbility
import io.kotest.matchers.shouldBe

class PossessedAvenScenarioTest : ScenarioTestBase() {
    init {
        test("threshold gives it +1/+1 and lets it destroy a blue creature") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Possessed Aven")
                .withLandsOnBattlefield(1, "Swamp", 3)
                .withCardOnBattlefield(2, "Wind Drake")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .apply { repeat(7) { withCardInGraveyard(1, "Island") } }
                .build()
            val aven = game.findPermanent("Possessed Aven")!!
            val destroyAbility = PossessedAven.staticAbilities
                .filterIsInstance<ConditionalStaticAbility>()
                .mapNotNull { it.ability as? GrantActivatedAbility }
                .single().ability.id

            game.state.projectedState.getPower(aven) shouldBe 4
            game.state.projectedState.getToughness(aven) shouldBe 4
            game.execute(
                ActivateAbility(
                    playerId = game.player1Id,
                    sourceId = aven,
                    abilityId = destroyAbility,
                    targets = listOf(ChosenTarget.Permanent(game.findPermanent(2, "Wind Drake")!!))
                )
            ).error shouldBe null
            game.resolveStack()
            game.findPermanent(2, "Wind Drake") shouldBe null
        }
    }
}
