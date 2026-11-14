package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.ody.cards.ResilientWanderer
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ResilientWandererScenarioTest : FunSpec({
    test("has first strike") {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + ResilientWanderer)
        driver.initMirrorMatch(deck = Deck.of("Plains" to 40))
        val wanderer = driver.putCreatureOnBattlefield(driver.activePlayer!!, "Resilient Wanderer")

        driver.state.projectedState.hasKeyword(wanderer, Keyword.FIRST_STRIKE) shouldBe true
    }
})
