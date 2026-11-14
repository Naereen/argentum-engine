package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.Outcome
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.ody.cards.DruidsCall
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class DruidsCallScenarioTest : FunSpec({
    test("creates Squirrels for the enchanted creature's controller when it is damaged") {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + DruidsCall)
        driver.initMirrorMatch(deck = Deck.of("Plains" to 40, "Forest" to 40))
        val you = driver.activePlayer!!
        val opponent = driver.getOpponent(you)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        val host = driver.putCreatureOnBattlefield(opponent, "Centaur Courser")
        val aura = driver.putCardInHand(you, "Druid's Call")
        driver.giveMana(you, Color.GREEN, 2)
        driver.castSpell(you, aura, listOf(host)).outcome shouldBe Outcome.Done
        driver.bothPass()

        val attacker = driver.putCreatureOnBattlefield(you, "Savannah Lions")
        driver.removeSummoningSickness(attacker)
        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        driver.declareAttackers(you, listOf(attacker), opponent)
        driver.bothPass()
        driver.declareBlockers(opponent, mapOf(host to listOf(attacker)))
        driver.passPriorityUntil(Step.POSTCOMBAT_MAIN)

        driver.findPermanent(opponent, "Squirrel") shouldNotBe null
    }
})
