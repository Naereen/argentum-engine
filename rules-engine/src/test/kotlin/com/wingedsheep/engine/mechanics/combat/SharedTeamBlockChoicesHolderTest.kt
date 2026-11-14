package com.wingedsheep.engine.mechanics.combat

import com.wingedsheep.engine.core.CardEntityFactory
import com.wingedsheep.engine.core.EngineServices
import com.wingedsheep.engine.legalactions.EnumerationMode
import com.wingedsheep.engine.otaria.OtariaEncounter
import com.wingedsheep.engine.otaria.OtariaExplorationPile
import com.wingedsheep.engine.otaria.OtariaExplorationSource
import com.wingedsheep.engine.otaria.OtariaLocation
import com.wingedsheep.engine.otaria.OtariaPlayerState
import com.wingedsheep.engine.otaria.OtariaPosition
import com.wingedsheep.engine.otaria.OtariaState
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.combat.AttackingComponent
import com.wingedsheep.engine.state.components.combat.BeingAttackedComponent
import com.wingedsheep.engine.state.components.combat.TeamBlockerChoicesComponent
import com.wingedsheep.engine.state.components.identity.PlayerComponent
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SharedTeamBlockChoicesHolderTest : FunSpec({
    val explorer = EntityId("explorer")
    val manager = EntityId("manager")
    val actor = EntityId("encounter")
    val attacker = EntityId("attacker")
    val defender = EntityId("defender")
    val reinforcement = EntityId("reinforcement")
    val creature = card("Block choice test creature") {
        typeLine = "Creature"
        power = 2
        toughness = 2
    }
    val registry = CardRegistry().also { it.register(creature) }
    val services = EngineServices(registry)

    fun encounterState(withReinforcement: Boolean): GameState {
        var state = GameState(
            entities = listOf(explorer, manager, actor).associateWith {
                ComponentContainer.of(PlayerComponent(it.value))
            },
            turnOrder = listOf(explorer, manager),
            activePlayerId = explorer,
            priorityPlayerId = actor,
            step = Step.DECLARE_BLOCKERS,
            phase = Step.DECLARE_BLOCKERS.phase,
        )
        fun add(id: EntityId, owner: EntityId) {
            state = state.withEntity(id, CardEntityFactory.create(creature, owner))
                .addToZone(ZoneKey(owner, Zone.BATTLEFIELD), id)
        }
        add(attacker, explorer)
        add(defender, actor)
        if (withReinforcement) add(reinforcement, actor)
        state = state.updateEntity(attacker) {
            it.with(AttackingComponent(defender, defendingPlayerId = actor))
        }.updateEntity(defender) {
            it.with(BeingAttackedComponent(controllerId = actor, protectorId = null))
        }
        val position = OtariaPosition(6, 5)
        val encounter = OtariaEncounter(
            explorerId = explorer,
            managerId = manager,
            position = position,
            source = OtariaExplorationSource.Terrain(Color.GREEN),
            primaryId = defender,
            members = listOfNotNull(
                state.objectRef(defender),
                if (withReinforcement) state.objectRef(reinforcement) else null,
            ).toSet(),
        )
        return state.copy(otaria = OtariaState(
            players = mapOf(
                explorer to OtariaPlayerState(OtariaLocation.KROSIAN_FOREST, position),
                manager to OtariaPlayerState(OtariaLocation.ORDER_ENCAMPMENT, OtariaPosition(7, 5)),
            ),
            explorationsUsed = emptyMap(),
            starPile = OtariaExplorationPile(emptyList()),
            colorPiles = emptyMap(),
            treasurePile = emptyList(),
            locationPile = emptyList(),
            encounters = mapOf(actor to encounter),
        ))
    }

    test("a non-team encounter actor outside turn order owns its block choice metadata") {
        val initial = encounterState(withReinforcement = true)
        (actor in initial.turnOrder) shouldBe false
        SharedTeamBlockChoices.requiresSeparateChoices(initial, actor) shouldBe false
        SharedTeamBlockChoices.problem(initial, actor) shouldBe null
        SharedTeamBlockChoices.proposals(initial, actor) shouldBe emptyMap()

        val assignments = mapOf(reinforcement to listOf(attacker))
        val state = initial.updateEntity(actor) {
            it.with(TeamBlockerChoicesComponent(
                choices = mapOf(actor to assignments),
                error = "Retry encounter blockers",
            ))
        }
        SharedTeamBlockChoices.problem(state, actor) shouldBe "Retry encounter blockers"
        SharedTeamBlockChoices.proposals(state, actor) shouldBe assignments
        SharedTeamBlockChoices.problem(state, manager) shouldBe null
        SharedTeamBlockChoices.proposals(state, manager) shouldBe emptyMap()
    }

    for (mode in EnumerationMode.entries) {
        for (withReinforcement in listOf(false, true)) {
            test("encounter blockers enumerate in $mode with reinforcement=$withReinforcement") {
                val state = encounterState(withReinforcement)
                val action = services.legalActionEnumerator.enumerate(state, actor, mode).single()
                action.actionType shouldBe "DeclareBlockers"
                action.description shouldBe "Declare blockers"
                action.proposedBlockerAssignments shouldBe null
                action.blockableAttackers shouldBe listOf(attacker)
                action.validBlockers shouldBe if (withReinforcement) listOf(reinforcement) else emptyList()
            }
        }
    }
})
