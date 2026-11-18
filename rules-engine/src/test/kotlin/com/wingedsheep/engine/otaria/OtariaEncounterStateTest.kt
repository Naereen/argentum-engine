package com.wingedsheep.engine.otaria

import com.wingedsheep.engine.core.ActionProcessor
import com.wingedsheep.engine.core.CardEntityFactory
import com.wingedsheep.engine.core.CleanupPhaseManager
import com.wingedsheep.engine.core.Concede
import com.wingedsheep.engine.core.EngineServices
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.engine.event.PendingTrigger
import com.wingedsheep.engine.event.TriggerContext
import com.wingedsheep.engine.event.TriggerMatcher
import com.wingedsheep.engine.handlers.PredicateEvaluator
import com.wingedsheep.engine.mechanics.combat.CombatDefenders
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.DamageComponent
import com.wingedsheep.engine.state.components.combat.AttackingComponent
import com.wingedsheep.engine.state.components.identity.LifeTotalComponent
import com.wingedsheep.engine.state.components.identity.PlayerComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.scripting.AbilityId
import com.wingedsheep.sdk.scripting.TriggeredAbility
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class OtariaEncounterStateTest : FunSpec({
    val players = (1..3).map { EntityId("player-$it") }
    val explorer = players.first()
    val actor = EntityId("encounter")
    val primary = EntityId("primary")
    val reinforcement = EntityId("reinforcement")
    val ordinary = EntityId("ordinary")
    val creature = CardDefinition.creature(
        name = "Encounter Test Creature",
        manaCost = ManaCost.parse("{G}"),
        subtypes = setOf(Subtype.ELF),
        power = 2,
        toughness = 3,
    )
    val registry = CardRegistry().also { it.register(creature) }
    val services = EngineServices(registry)
    val processor = ActionProcessor(services)
    val json = Json { serializersModule = engineSerializersModule; allowStructuredMapKeys = true }

    fun state(): GameState {
        val mode = OtariaState(
            players = players.zip(OtariaLocation.entries).associate { (id, location) ->
                id to OtariaPlayerState(location, location.position)
            },
            explorationsUsed = OtariaLocation.entries.associateWith { 0 },
            starPile = OtariaExplorationPile(emptyList()),
            colorPiles = Color.entries.associateWith { OtariaExplorationPile(emptyList()) },
            treasurePile = emptyList(),
            locationPile = emptyList(),
            turn = OtariaTurn(explorer, 1, OtariaTurnAction.COMBAT),
        )
        val entities = (players + actor).associateWith {
            ComponentContainer.of(PlayerComponent(it.value, 15), LifeTotalComponent(15), ManaPoolComponent())
        }
        var current = GameState(
            entities = entities,
            turnOrder = players,
            activePlayerId = explorer,
            priorityPlayerId = explorer,
            turnNumber = 1,
            step = Step.PRECOMBAT_MAIN,
            phase = Step.PRECOMBAT_MAIN.phase,
            otaria = mode,
        )
        for ((id, owner) in listOf(primary to actor, reinforcement to actor, ordinary to explorer)) {
            current = current.withEntity(id, CardEntityFactory.create(creature, owner).with(DamageComponent(1)))
                .addToZone(ZoneKey(owner, Zone.BATTLEFIELD), id)
        }
        val encounter = OtariaEncounter(
            explorerId = explorer,
            managerId = players[1],
            position = mode.players.getValue(explorer).position,
            source = OtariaExplorationSource.Terrain(Color.GREEN),
            primaryId = primary,
            members = setOf(requireNotNull(current.objectRef(primary)), requireNotNull(current.objectRef(reinforcement))),
        )
        return current.copy(otaria = mode.copy(encounters = mapOf(actor to encounter)))
    }

    test("encounter state and object identities survive serialization") {
        val initial = state()
        val restored = json.decodeFromString<GameState>(json.encodeToString(initial))
        restored shouldBe initial
        restored.actorFor(actor) shouldBe players[1]
        restored.activePlayers shouldBe players
        restored.priorityParticipants shouldBe listOf(explorer, actor, players[1], players[2])
    }

    test("an encounter receives priority without becoming a seated player") {
        val initial = state()
        val afterExplorer = processor.process(initial, PassPriority(explorer)).result
        afterExplorer.error shouldBe null
        afterExplorer.state.priorityPlayerId shouldBe actor
        afterExplorer.state.apnapOrder shouldBe listOf(explorer, actor, players[1], players[2])
        processor.validate(afterExplorer.state, PassPriority(actor)) shouldBe null
        processor.validate(afterExplorer.state, Concede(actor)) shouldNotBe null
        val afterEncounter = processor.process(afterExplorer.state, PassPriority(actor)).result
        afterEncounter.error shouldBe null
        afterEncounter.state.priorityPlayerId shouldBe players[1]
        afterEncounter.state.allPlayersPassed() shouldBe false
        afterEncounter.state.withPriorityPassed(players[1]).withPriorityPassed(players[2])
            .allPlayersPassed() shouldBe true
        afterEncounter.state.activePlayers shouldBe players
    }

    test("priority skips seats and encounters outside the active player's influence") {
        val initial = state()
        val mode = requireNotNull(initial.otaria)
        val far = OtariaPosition(10, 11)
        (OtariaBoard.distance(mode.players.getValue(explorer).position, far) <= 2L) shouldBe false
        val withFarSeat = initial.copy(otaria = mode.copy(
            players = mode.players + (players[2] to mode.players.getValue(players[2]).copy(position = far)),
        ))
        withFarSeat.priorityParticipants shouldBe listOf(explorer, actor, players[1], players[2])
        withFarSeat.otariaPriorityParticipants shouldBe listOf(explorer, actor, players[1])

        val passedExplorer = withFarSeat.withPriorityPassed(explorer)
        passedExplorer.nextPriorityAfterPass(explorer) shouldBe actor
        val passedEncounter = passedExplorer.withPriorityPassed(actor)
        passedEncounter.nextPriorityAfterPass(actor) shouldBe players[1]

        val allInRangePassed = passedEncounter
            .withPriorityPassed(players[1])
        allInRangePassed.allPlayersPassed() shouldBe true
    }

    test("an encounter's triggers are retained in active-player order") {
        val current = state()
        val evaluator = PredicateEvaluator(registry)
        val matcher = TriggerMatcher(evaluator, evaluator.conditions)
        val ability = TriggeredAbility(
            id = AbilityId("encounter-trigger"),
            trigger = Triggers.self.enters().event,
            effect = Effects.GainLife(1),
        )
        fun trigger(controller: EntityId) = PendingTrigger(
            ability = ability,
            sourceId = primary,
            sourceName = creature.name,
            controllerId = controller,
            triggerContext = TriggerContext(),
        )
        val triggers = listOf(trigger(players[2]), trigger(actor), trigger(explorer), trigger(players[1]))
        matcher.sortByApnapOrder(current, triggers).map { it.controllerId } shouldBe
            listOf(explorer, actor, players[1], players[2])
        matcher.sortByApnapOrder(current.copy(activePlayerId = actor), triggers).map { it.controllerId } shouldBe
            listOf(actor, players[1], players[2], explorer)
    }

    test("an encounter defending a permanent is included in blocker declaration order") {
        val current = state().updateEntity(ordinary) {
            it.with(AttackingComponent(defenderId = primary, defendingPlayerId = actor))
        }
        CombatDefenders.defendingPlayersInApnapOrder(current) shouldBe listOf(actor)
        CombatDefenders.blockableAttackers(current, actor) shouldBe listOf(ordinary)
    }

    test("an encounter takes its turn after its explorer without advancing the round") {
        val initial = state()
        val encounterTurn = services.turnManager.passTurn(initial)
        encounterTurn.error shouldBe null
        encounterTurn.state.activePlayerId shouldBe actor
        encounterTurn.state.priorityPlayerId shouldBe actor
        encounterTurn.state.roundNumber shouldBe initial.roundNumber
        encounterTurn.state.turnOrder shouldBe players
        encounterTurn.state.activePlayers shouldBe players

        val nextSeat = services.turnManager.passTurn(encounterTurn.state)
        nextSeat.error shouldBe null
        nextSeat.state.activePlayerId shouldBe players[1]
        nextSeat.state.roundNumber shouldBe initial.roundNumber
        nextSeat.state.pendingDecision?.playerId shouldBe players[1]
    }

    test("an encounter turn after the final seat wraps the round only for the next human") {
        val initial = state()
        val mode = requireNotNull(initial.otaria)
        val encounter = mode.encounters.getValue(actor)
        val lastSeat = players.last()
        val lastTurn = initial.copy(
            activePlayerId = lastSeat,
            priorityPlayerId = lastSeat,
            roundNumber = 3,
            otaria = mode.copy(encounters = mapOf(actor to encounter.copy(explorerId = lastSeat))),
        )
        val encounterTurn = services.turnManager.passTurn(lastTurn)
        encounterTurn.error shouldBe null
        encounterTurn.state.activePlayerId shouldBe actor
        encounterTurn.state.roundNumber shouldBe 3
        val nextRound = services.turnManager.passTurn(encounterTurn.state)
        nextRound.error shouldBe null
        nextRound.state.activePlayerId shouldBe explorer
        nextRound.state.roundNumber shouldBe 4
    }

    test("only the same encountered creatures retain marked damage at cleanup") {
        val initial = state()
        val cleaned = CleanupPhaseManager.applyCleanupTurnBasedActions(initial, registry)
        cleaned.getEntity(primary)?.get<DamageComponent>()?.amount shouldBe 1
        cleaned.getEntity(reinforcement)?.get<DamageComponent>()?.amount shouldBe 1
        cleaned.getEntity(ordinary)?.get<DamageComponent>() shouldBe null
        initial.getEntity(ordinary)?.get<DamageComponent>()?.amount shouldBe 1

        val battlefield = ZoneKey(actor, Zone.BATTLEFIELD)
        val exile = ZoneKey(actor, Zone.EXILE)
        val returned = initial.removeFromZone(battlefield, primary).addToZone(exile, primary)
            .removeFromZone(exile, primary).addToZone(battlefield, primary)
        returned.objectRef(primary) shouldNotBe initial.objectRef(primary)
        val cleanedAfterReturn = CleanupPhaseManager.applyCleanupTurnBasedActions(returned, registry)
        cleanedAfterReturn.getEntity(primary)?.get<DamageComponent>() shouldBe null
        cleanedAfterReturn.getEntity(reinforcement)?.get<DamageComponent>()?.amount shouldBe 1

        val normalGame = initial.copy(otaria = null)
        CleanupPhaseManager.applyCleanupTurnBasedActions(normalGame, registry)
            .getEntity(primary)?.get<DamageComponent>() shouldBe null
    }
})
