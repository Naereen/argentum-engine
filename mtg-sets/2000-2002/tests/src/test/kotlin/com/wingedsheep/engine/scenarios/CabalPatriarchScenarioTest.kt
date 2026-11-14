package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.Outcome
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.ody.cards.CabalPatriarch
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.AdditionalCostPayment
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CabalPatriarchScenarioTest : FunSpec({
    test("sacrifices a creature to give a target creature -2/-2") {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + CabalPatriarch)
        driver.initMirrorMatch(deck = Deck.of("Swamp" to 40))
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val you = driver.activePlayer!!
        val opponent = driver.getOpponent(you)
        val patriarch = driver.putCreatureOnBattlefield(you, "Cabal Patriarch")
        val sacrifice = driver.putCreatureOnBattlefield(you, "Savannah Lions")
        val target = driver.putCreatureOnBattlefield(opponent, "Centaur Courser")
        driver.giveMana(you, Color.BLACK, 3)

        driver.submit(
            ActivateAbility(
                playerId = you,
                sourceId = patriarch,
                abilityId = CabalPatriarch.activatedAbilities[0].id,
                targets = listOf(ChosenTarget.Permanent(target)),
                costPayment = AdditionalCostPayment(sacrificedPermanents = listOf(sacrifice))
            )
        ).outcome shouldBe Outcome.Done
        driver.bothPass()

        driver.state.projectedState.getPower(target) shouldBe 1
        driver.state.projectedState.getToughness(target) shouldBe 1
    }
})
