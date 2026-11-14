package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.ody.cards.Werebear
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class WerebearScenarioTest : FunSpec({
    test("gets +3/+3 at threshold") {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + Werebear)
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40))
        val you = driver.activePlayer!!
        repeat(7) { driver.putCardInGraveyard(you, "Forest") }
        val bear = driver.putCreatureOnBattlefield(you, "Werebear")

        driver.state.projectedState.getPower(bear) shouldBe 4
        driver.state.projectedState.getToughness(bear) shouldBe 4
    }
})
