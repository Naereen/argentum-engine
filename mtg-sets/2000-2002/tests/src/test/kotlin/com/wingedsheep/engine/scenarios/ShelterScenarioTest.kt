package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ChooseColorDecision
import com.wingedsheep.engine.core.ColorChosenResponse
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.ody.cards.Shelter
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class ShelterScenarioTest : FunSpec({
    test("grants chosen-color protection and draws a card") {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + Shelter)
        driver.initMirrorMatch(deck = Deck.of("Plains" to 40))
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val you = driver.activePlayer!!
        val creature = driver.putCreatureOnBattlefield(you, "Savannah Lions")
        val spell = driver.putCardInHand(you, "Shelter")
        driver.giveMana(you, Color.WHITE, 2)
        val handBefore = driver.getHandSize(you)

        driver.castSpell(you, spell, listOf(creature))
        driver.bothPass()
        val decision = driver.pendingDecision.shouldBeInstanceOf<ChooseColorDecision>()
        driver.submitDecision(you, ColorChosenResponse(decision.id, Color.RED))

        driver.state.projectedState.hasKeyword(creature, "PROTECTION_FROM_RED") shouldBe true
        driver.getHandSize(you) shouldBe handBefore
    }
})
