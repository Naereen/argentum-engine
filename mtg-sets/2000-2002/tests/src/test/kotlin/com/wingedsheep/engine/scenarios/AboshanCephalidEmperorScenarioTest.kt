package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.Outcome
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.ody.cards.AboshanCephalidEmperor
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.AdditionalCostPayment
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AboshanCephalidEmperorScenarioTest : FunSpec({

    fun driver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + listOf(AboshanCephalidEmperor))
        driver.initMirrorMatch(deck = Deck.of("Island" to 40))
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    test("taps target permanent by tapping an untapped Octopus") {
        val driver = driver()
        val you = driver.activePlayer!!
        val opponent = driver.getOpponent(you)
        val aboshan = driver.putCreatureOnBattlefield(you, "Aboshan, Cephalid Emperor")
        val forest = driver.putLandOnBattlefield(opponent, "Forest")

        driver.submit(
            ActivateAbility(
                playerId = you,
                sourceId = aboshan,
                abilityId = AboshanCephalidEmperor.activatedAbilities[0].id,
                targets = listOf(ChosenTarget.Permanent(forest)),
                costPayment = AdditionalCostPayment(tappedPermanents = listOf(aboshan))
            )
        ).outcome shouldBe Outcome.Done

        driver.bothPass()

        driver.isTapped(aboshan) shouldBe true
        driver.isTapped(forest) shouldBe true
    }

    test("taps all creatures without flying") {
        val driver = driver()
        val you = driver.activePlayer!!
        val opponent = driver.getOpponent(you)
        val aboshan = driver.putCreatureOnBattlefield(you, "Aboshan, Cephalid Emperor")
        val groundCreature = driver.putCreatureOnBattlefield(you, "Grizzly Bears")
        val flyer = driver.putCreatureOnBattlefield(opponent, "Wind Drake")
        driver.giveMana(you, Color.BLUE, 3)

        driver.submit(
            ActivateAbility(
                playerId = you,
                sourceId = aboshan,
                abilityId = AboshanCephalidEmperor.activatedAbilities[1].id
            )
        ).outcome shouldBe Outcome.Done

        driver.bothPass()

        driver.isTapped(aboshan) shouldBe true
        driver.isTapped(groundCreature) shouldBe true
        driver.isTapped(flyer) shouldBe false
    }
})
