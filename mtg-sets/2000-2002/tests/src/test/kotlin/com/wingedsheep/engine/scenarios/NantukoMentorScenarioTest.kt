package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.Outcome
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.ody.cards.NantukoMentor
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class NantukoMentorScenarioTest : FunSpec({
    test("gives a target creature +X/+X using its power for X") {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + NantukoMentor)
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40))
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val you = driver.activePlayer!!
        val mentor = driver.putCreatureOnBattlefield(you, "Nantuko Mentor")
        val target = driver.putCreatureOnBattlefield(you, "Centaur Courser")
        driver.giveMana(you, Color.GREEN, 3)

        driver.submit(
            ActivateAbility(
                playerId = you,
                sourceId = mentor,
                abilityId = NantukoMentor.activatedAbilities.single().id,
                targets = listOf(ChosenTarget.Permanent(target))
            )
        ).outcome shouldBe Outcome.Done
        driver.bothPass()

        driver.state.projectedState.getPower(target) shouldBe 6
        driver.state.projectedState.getToughness(target) shouldBe 6
    }
})
