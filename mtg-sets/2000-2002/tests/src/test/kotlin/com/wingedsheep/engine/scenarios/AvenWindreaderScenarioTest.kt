package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.CardsRevealedEvent
import com.wingedsheep.engine.core.Outcome
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.ody.cards.AvenWindreader
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AvenWindreaderScenarioTest : FunSpec({
    test("reveals the targeted player's top card") {
        val driver = GameTestDriver().apply {
            registerCards(TestCards.all + AvenWindreader)
            initMirrorMatch(deck = Deck.of("Island" to 40))
            passPriorityUntil(Step.PRECOMBAT_MAIN)
        }
        val you = driver.activePlayer!!
        val opponent = driver.getOpponent(you)
        val bird = driver.putCreatureOnBattlefield(you, "Aven Windreader")
        driver.giveMana(you, Color.BLUE, 2)

        driver.submit(
            ActivateAbility(
                playerId = you,
                sourceId = bird,
                abilityId = AvenWindreader.activatedAbilities.single().id,
                targets = listOf(ChosenTarget.Player(opponent))
            )
        ).outcome shouldBe Outcome.Done

        driver.bothPass()
        driver.events.filterIsInstance<CardsRevealedEvent>().last().cardNames shouldBe listOf("Island")
    }
})
