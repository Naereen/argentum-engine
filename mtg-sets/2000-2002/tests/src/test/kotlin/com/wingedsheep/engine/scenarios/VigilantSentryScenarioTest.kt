package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.DeclareAttackers
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.mtg.sets.definitions.jud.cards.VigilantSentry
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.scripting.ConditionalStaticAbility
import com.wingedsheep.sdk.scripting.GrantActivatedAbility
import io.kotest.matchers.shouldBe

class VigilantSentryScenarioTest : ScenarioTestBase() {

    init {
        test("threshold grants +1/+1 and a tap ability that pumps an attacking creature") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Vigilant Sentry")
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withActivePlayer(1)
                .inPhase(Phase.COMBAT, Step.DECLARE_ATTACKERS)
                .apply { repeat(7) { withCardInGraveyard(1, "Island") } }
                .build()
            val sentry = game.findPermanent("Vigilant Sentry")!!
            val attacker = game.findPermanent("Grizzly Bears")!!
            val activatedAbilityId = VigilantSentry.staticAbilities
                .filterIsInstance<ConditionalStaticAbility>()
                .mapNotNull { it.ability as? GrantActivatedAbility }
                .single()
                .ability.id

            game.state.projectedState.getPower(sentry) shouldBe 3
            game.state.projectedState.getToughness(sentry) shouldBe 3
            game.execute(
                DeclareAttackers(game.player1Id, mapOf(attacker to game.player2Id)),
            ).error shouldBe null
            game.execute(
                ActivateAbility(
                    playerId = game.player1Id,
                    sourceId = sentry,
                    abilityId = activatedAbilityId,
                    targets = listOf(ChosenTarget.Permanent(attacker)),
                ),
            ).error shouldBe null
            game.resolveStack()

            game.state.projectedState.getPower(attacker) shouldBe 5
            game.state.projectedState.getToughness(attacker) shouldBe 5
        }
    }
}
