package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.mtg.sets.definitions.tor.cards.PossessedBarbarian
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.scripting.ConditionalStaticAbility
import com.wingedsheep.sdk.scripting.GrantActivatedAbility
import io.kotest.matchers.shouldBe

class PossessedBarbarianScenarioTest : ScenarioTestBase() {
    init {
        test("threshold gives it +1/+1 and lets it destroy a red creature") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Possessed Barbarian")
                .withLandsOnBattlefield(1, "Swamp", 3)
                .withCardOnBattlefield(2, "Goblin Guide")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .apply { repeat(7) { withCardInGraveyard(1, "Mountain") } }
                .build()
            val barbarian = game.findPermanent("Possessed Barbarian")!!
            val destroyAbility = PossessedBarbarian.staticAbilities
                .filterIsInstance<ConditionalStaticAbility>()
                .mapNotNull { it.ability as? GrantActivatedAbility }
                .single().ability.id

            game.state.projectedState.getPower(barbarian) shouldBe 4
            game.state.projectedState.getToughness(barbarian) shouldBe 4
            game.execute(
                ActivateAbility(
                    playerId = game.player1Id,
                    sourceId = barbarian,
                    abilityId = destroyAbility,
                    targets = listOf(ChosenTarget.Permanent(game.findPermanent(2, "Goblin Guide")!!))
                )
            ).error shouldBe null
            game.resolveStack()
            game.findPermanent(2, "Goblin Guide") shouldBe null
        }
    }
}
