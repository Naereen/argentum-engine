package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.state.components.player.ExtraTurnsComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.ody.cards.TimeStretch
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TimeStretchScenarioTest : FunSpec({
    test("gives the targeted player two extra turns") {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + TimeStretch)
        driver.initMirrorMatch(deck = Deck.of("Island" to 40))
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val you = driver.activePlayer!!
        val opponent = driver.getOpponent(you)
        val spell = driver.putCardInHand(you, "Time Stretch")
        driver.giveMana(you, Color.BLUE, 10)

        driver.castSpellWithTargets(you, spell, listOf(com.wingedsheep.engine.state.components.stack.ChosenTarget.Player(opponent)))
        driver.bothPass()

        driver.state.getEntity(opponent)?.get<ExtraTurnsComponent>()?.pending?.size shouldBe 2
    }
})
