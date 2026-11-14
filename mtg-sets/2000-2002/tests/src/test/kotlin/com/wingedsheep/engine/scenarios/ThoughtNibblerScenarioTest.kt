package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.ody.cards.ThoughtNibbler
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ThoughtNibblerScenarioTest : FunSpec({
    test("has flying") {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + ThoughtNibbler)
        driver.initMirrorMatch(deck = Deck.of("Island" to 40))
        val nibbler = driver.putCreatureOnBattlefield(driver.activePlayer!!, "Thought Nibbler")

        driver.state.projectedState.hasKeyword(nibbler, Keyword.FLYING) shouldBe true
    }
})
