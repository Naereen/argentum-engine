package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.ody.cards.SpringingTiger
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SpringingTigerScenarioTest : FunSpec({
    test("gets +2/+2 at threshold") {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + SpringingTiger)
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40))
        val you = driver.activePlayer!!
        repeat(7) { driver.putCardInGraveyard(you, "Forest") }
        val tiger = driver.putCreatureOnBattlefield(you, "Springing Tiger")

        driver.state.projectedState.getPower(tiger) shouldBe 5
        driver.state.projectedState.getToughness(tiger) shouldBe 5
    }
})
