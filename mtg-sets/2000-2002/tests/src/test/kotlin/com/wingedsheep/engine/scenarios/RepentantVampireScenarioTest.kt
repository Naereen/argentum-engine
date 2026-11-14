package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.ody.cards.RepentantVampire
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class RepentantVampireScenarioTest : FunSpec({
    test("threshold makes it white") {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + RepentantVampire)
        driver.initMirrorMatch(deck = Deck.of("Swamp" to 40))
        val you = driver.activePlayer!!
        repeat(7) { driver.putCardInGraveyard(you, "Swamp") }
        val vampire = driver.putCreatureOnBattlefield(you, "Repentant Vampire")

        driver.state.projectedState.hasColor(vampire, Color.WHITE) shouldBe true
    }
})
