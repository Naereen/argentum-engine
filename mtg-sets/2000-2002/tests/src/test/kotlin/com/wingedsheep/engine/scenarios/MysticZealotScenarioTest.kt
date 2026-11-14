package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.ody.cards.MysticZealot
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MysticZealotScenarioTest : FunSpec({
    test("threshold grants +1/+1 and flying") {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + MysticZealot)
        driver.initMirrorMatch(deck = Deck.of("Plains" to 40))
        val you = driver.activePlayer!!
        repeat(7) { driver.putCardInGraveyard(you, "Plains") }
        val zealot = driver.putCreatureOnBattlefield(you, "Mystic Zealot")

        driver.state.projectedState.getPower(zealot) shouldBe 3
        driver.state.projectedState.getToughness(zealot) shouldBe 5
        driver.state.projectedState.hasKeyword(zealot, Keyword.FLYING) shouldBe true
    }
})
