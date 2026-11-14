package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.ody.cards.Spiritualize
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SpiritualizeScenarioTest : FunSpec({
    test("draws a card and gains life when the targeted creature deals damage") {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + Spiritualize)
        driver.initMirrorMatch(deck = Deck.of("Plains" to 40))
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val you = driver.activePlayer!!
        val opponent = driver.getOpponent(you)
        val creature = driver.putCreatureOnBattlefield(you, "Savannah Lions")
        driver.removeSummoningSickness(creature)
        val spell = driver.putCardInHand(you, "Spiritualize")
        driver.giveMana(you, Color.WHITE, 3)
        val handBefore = driver.getHandSize(you)
        val lifeBefore = driver.getLifeTotal(you)

        driver.castSpell(you, spell, listOf(creature))
        driver.bothPass()
        driver.getHandSize(you) shouldBe handBefore

        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        driver.declareAttackers(you, listOf(creature), opponent)
        driver.passPriorityUntil(Step.POSTCOMBAT_MAIN)

        driver.getLifeTotal(you) shouldBe lifeBefore + 1
    }
})
