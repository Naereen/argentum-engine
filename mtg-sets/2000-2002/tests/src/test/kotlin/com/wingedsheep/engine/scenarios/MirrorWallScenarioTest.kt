package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.state.components.combat.CanAttackDespiteDefenderThisTurnComponent
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.mtg.sets.definitions.jud.cards.MirrorWall
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.matchers.shouldBe

class MirrorWallScenarioTest : ScenarioTestBase() {

    init {
        test("{W} lets Mirror Wall attack despite defender for the turn") {
            val game = scenario()
                .withPlayers()
                .withLandsOnBattlefield(1, "Plains", 1)
                .withCardOnBattlefield(1, "Mirror Wall")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()
            val wall = game.findPermanent("Mirror Wall")!!

            game.state.projectedState.cantAttack(wall) shouldBe true
            game.execute(
                ActivateAbility(
                    playerId = game.player1Id,
                    sourceId = wall,
                    abilityId = MirrorWall.activatedAbilities.single().id,
                ),
            ).error shouldBe null
            game.resolveStack()

            game.state.getEntity(wall)!!.has<CanAttackDespiteDefenderThisTurnComponent>() shouldBe true
            game.state.projectedState.cantAttack(wall) shouldBe false
        }
    }
}
