package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.Outcome
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.ody.cards.Dreamwinder
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class DreamwinderScenarioTest : FunSpec({
    test("cannot attack unless the defending player controls an Island") {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + Dreamwinder)
        driver.initMirrorMatch(deck = Deck.of("Island" to 40))
        val you = driver.activePlayer!!
        val opponent = driver.getOpponent(you)
        val serpent = driver.putCreatureOnBattlefield(you, "Dreamwinder")
        driver.removeSummoningSickness(serpent)
        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)

        driver.declareAttackers(you, listOf(serpent), opponent).outcome shouldNotBe Outcome.Done
        driver.putLandOnBattlefield(opponent, "Island")
        driver.declareAttackers(you, listOf(serpent), opponent).outcome shouldBe Outcome.Done
    }
})
