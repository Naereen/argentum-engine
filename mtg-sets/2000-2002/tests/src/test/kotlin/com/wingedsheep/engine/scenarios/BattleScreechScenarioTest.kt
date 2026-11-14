package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.AlternativeCostType
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.state.components.battlefield.TappedComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.TokenComponent
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.scripting.AdditionalCostPayment
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class BattleScreechScenarioTest : ScenarioTestBase() {

    init {
        test("creates two white Bird tokens with flying") {
            val game = scenario()
                .withPlayers()
                .withLandsOnBattlefield(1, "Plains", 4)
                .withCardInHand(1, "Battle Screech")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()
            val screech = game.findCardsInHand(1, "Battle Screech").single()

            game.execute(CastSpell(game.player1Id, screech)).error shouldBe null
            game.resolveStack()

            val birds = game.state.getBattlefield().filter { id ->
                val entity = game.state.getEntity(id)
                entity?.has<TokenComponent>() == true &&
                    entity?.get<CardComponent>()?.typeLine?.subtypes?.any { it.value == "Bird" } == true
            }
            birds shouldHaveSize 2
            birds.forEach { bird ->
                game.state.getEntity(bird)!!.get<CardComponent>()!!.colors shouldBe setOf(Color.WHITE)
                game.state.projectedState.hasKeyword(bird, Keyword.FLYING) shouldBe true
            }
        }

        test("flashback taps three untapped white creatures and exiles the spell") {
            val builder = scenario()
                .withPlayers()
                .withCardInGraveyard(1, "Battle Screech")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
            repeat(3) { builder.withCardOnBattlefield(1, "Suntail Hawk") }
            val game = builder.build()
            val screech = game.findCardsInGraveyard(1, "Battle Screech").single()
            val hawks = game.findPermanents("Suntail Hawk")

            game.execute(
                CastSpell(
                    playerId = game.player1Id,
                    cardId = screech,
                    useAlternativeCost = true,
                    alternativeCostType = AlternativeCostType.FLASHBACK,
                    additionalCostPayment = AdditionalCostPayment(tappedPermanents = hawks),
                ),
            ).error shouldBe null
            game.resolveStack()

            hawks.forEach { hawk ->
                game.state.getEntity(hawk)!!.has<TappedComponent>() shouldBe true
            }
            game.state.getZone(ZoneKey(game.player1Id, Zone.EXILE)).contains(screech) shouldBe true
        }
    }
}
