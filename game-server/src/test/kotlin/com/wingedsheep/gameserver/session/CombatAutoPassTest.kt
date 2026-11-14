package com.wingedsheep.gameserver.session

import com.wingedsheep.engine.core.CardEntityFactory
import com.wingedsheep.engine.core.DeclareAttackers
import com.wingedsheep.engine.core.DeclareBlockers
import com.wingedsheep.engine.otaria.*
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.combat.BlockersDeclaredThisCombatComponent
import com.wingedsheep.engine.state.components.identity.LifeTotalComponent
import com.wingedsheep.engine.state.components.identity.PlayerComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.sdk.core.*
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class CombatAutoPassTest : FunSpec({
    val explorer = EntityId("explorer")
    val manager = EntityId("manager")
    val bystander = EntityId("bystander")
    val actor = EntityId("encounter")
    val attacker = EntityId("attacker")
    val blocker = EntityId("blocker")
    val players = listOf(explorer, manager, bystander)
    val flying = CardDefinition.creature(
        "Flying encounter", ManaCost.ZERO, emptySet(), 6, 6,
        keywords = setOf(Keyword.FLYING, Keyword.FIRST_STRIKE, Keyword.TRAMPLE, Keyword.HASTE),
    )
    val reach = CardDefinition.creature(
        "Reach defender", ManaCost.ZERO, emptySet(), 3, 2, keywords = setOf(Keyword.REACH),
    )
    val registry = CardRegistry().also { listOf(flying, reach).forEach(it::register) }

    fun session(encounter: Boolean): GameSession {
        val attackingPlayer = if (encounter) actor else manager
        val mode = if (encounter) OtariaState(
            players = players.associateWith {
                OtariaPlayerState(OtariaLocation.KROSIAN_FOREST, OtariaLocation.KROSIAN_FOREST.position)
            },
            explorationsUsed = OtariaLocation.entries.associateWith { 0 },
            starPile = OtariaExplorationPile(emptyList()),
            colorPiles = Color.entries.associateWith { OtariaExplorationPile(emptyList()) },
            treasurePile = emptyList(),
            locationPile = emptyList(),
        ) else null
        var state = GameState(
            entities = (players + if (encounter) listOf(actor) else emptyList()).associateWith {
                ComponentContainer.of(PlayerComponent(it.value, 15), LifeTotalComponent(15), ManaPoolComponent())
            },
            turnOrder = players,
            activePlayerId = attackingPlayer,
            priorityPlayerId = attackingPlayer,
            step = Step.DECLARE_ATTACKERS,
            phase = Phase.COMBAT,
            turnNumber = 2,
            otaria = mode,
        ).withEntity(attacker, CardEntityFactory.create(flying, attackingPlayer))
            .addToZone(ZoneKey(attackingPlayer, Zone.BATTLEFIELD), attacker)
            .withEntity(blocker, CardEntityFactory.create(reach, explorer))
            .addToZone(ZoneKey(explorer, Zone.BATTLEFIELD), blocker)
        if (mode != null) {
            state = state.copy(otaria = mode.copy(encounters = mapOf(actor to OtariaEncounter(
                explorer, manager, OtariaLocation.KROSIAN_FOREST.position,
                OtariaExplorationSource.Terrain(Color.GREEN), attacker,
                members = setOf(requireNotNull(state.objectRef(attacker))),
            ))))
        }
        return GameSession(cardRegistry = registry, maxPlayers = 3).also { session ->
            session.injectStateForDevScenario(state)
            session.executeAction(manager, DeclareAttackers(attackingPlayer, mapOf(attacker to explorer)))
                .shouldBeInstanceOf<GameSession.ActionResult.Success>()
            repeat(state.priorityParticipants.size) {
                val current = requireNotNull(session.getStateForTesting())
                current.step shouldBe Step.DECLARE_ATTACKERS
                session.executeAutoPass(requireNotNull(current.priorityPlayerId))
                    .shouldBeInstanceOf<GameSession.ActionResult.Success>()
            }
            requireNotNull(session.getStateForTesting()).step shouldBe Step.DECLARE_BLOCKERS
        }
    }

    for (encounter in listOf(false, true)) {
        for (block in listOf(false, true)) {
            test("${if (encounter) "encounter" else "multiplayer"} combat advances past bystanders after ${if (block) "a reach block" else "no blocks"}") {
                val session = session(encounter)
                session.executeAction(explorer, DeclareBlockers(
                    explorer, if (block) mapOf(blocker to listOf(attacker)) else emptyMap(),
                )).shouldBeInstanceOf<GameSession.ActionResult.Success>()

                val participants = requireNotNull(session.getStateForTesting()).priorityParticipants
                repeat(participants.size) {
                    val current = requireNotNull(session.getStateForTesting())
                    current.step shouldBe Step.DECLARE_BLOCKERS
                    val result = session.executeAutoPass(requireNotNull(current.priorityPlayerId))
                    if (block && it == participants.lastIndex) {
                        result.shouldBeInstanceOf<GameSession.ActionResult.PausedForDecision>()
                    } else {
                        result.shouldBeInstanceOf<GameSession.ActionResult.Success>()
                    }
                }

                val after = requireNotNull(session.getStateForTesting())
                after.step shouldBe Step.FIRST_STRIKE_COMBAT_DAMAGE
                after.getEntity(bystander)?.has<BlockersDeclaredThisCombatComponent>() shouldBe false
            }
        }
    }
})
