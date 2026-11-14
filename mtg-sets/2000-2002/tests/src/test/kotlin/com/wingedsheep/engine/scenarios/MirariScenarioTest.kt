package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.Outcome
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.ody.cards.Mirari
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class MirariScenarioTest : FunSpec({

    fun driver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + listOf(Mirari))
        driver.initMirrorMatch(deck = Deck.of("Island" to 40))
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun castDeepAnalysis(driver: GameTestDriver, player: EntityId): Int {
        driver.putPermanentOnBattlefield(player, "Mirari")
        val spell = driver.putCardInHand(player, "Deep Analysis")
        driver.giveColorlessMana(player, 3)
        driver.giveMana(player, Color.BLUE, 1)
        driver.castSpell(player, spell).outcome shouldBe Outcome.Done
        return driver.getHandSize(player)
    }

    test("may pay three to copy an instant or sorcery spell") {
        val driver = driver()
        val player = driver.activePlayer!!
        val handAfterCast = castDeepAnalysis(driver, player)
        driver.giveColorlessMana(player, 3)

        driver.bothPass()
        driver.pendingDecision.shouldBeInstanceOf<YesNoDecision>()
        driver.submitYesNo(player, true).outcome shouldBe Outcome.Done

        driver.stackSize shouldBe 2
        driver.bothPass()
        driver.bothPass()

        driver.getHandSize(player) shouldBe handAfterCast + 4
    }

    test("may decline to copy the spell") {
        val driver = driver()
        val player = driver.activePlayer!!
        val handAfterCast = castDeepAnalysis(driver, player)

        driver.bothPass()
        driver.pendingDecision.shouldBeInstanceOf<YesNoDecision>()
        driver.submitYesNo(player, false).outcome shouldBe Outcome.Done
        driver.bothPass()

        driver.getHandSize(player) shouldBe handAfterCast + 2
    }
})
