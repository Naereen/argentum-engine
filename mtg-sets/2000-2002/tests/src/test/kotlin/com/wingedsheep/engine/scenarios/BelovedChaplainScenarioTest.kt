package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.ody.cards.BelovedChaplain
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BelovedChaplainScenarioTest : FunSpec({
    test("has protection from creatures") {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + BelovedChaplain)
        driver.initMirrorMatch(deck = Deck.of("Plains" to 40))
        val chaplain = driver.putCreatureOnBattlefield(driver.activePlayer!!, "Beloved Chaplain")

        driver.state.projectedState.hasKeyword(chaplain, "PROTECTION_FROM_CARDTYPE_CREATURE") shouldBe true
    }
})
