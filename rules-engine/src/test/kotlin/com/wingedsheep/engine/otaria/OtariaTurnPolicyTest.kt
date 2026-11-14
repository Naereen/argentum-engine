package com.wingedsheep.engine.otaria

import com.wingedsheep.engine.core.ActionProcessor
import com.wingedsheep.engine.core.CardEntityFactory
import com.wingedsheep.engine.core.ChooseOptionDecision
import com.wingedsheep.engine.core.EngineServices
import com.wingedsheep.engine.core.ExecutionResult
import com.wingedsheep.engine.core.OptionChosenResponse
import com.wingedsheep.engine.core.OtariaTurnActionChosenEvent
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.TappedComponent
import com.wingedsheep.engine.state.components.identity.LifeTotalComponent
import com.wingedsheep.engine.state.components.identity.PlayerComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class OtariaTurnPolicyTest : FunSpec({
    val players = (1..3).map { EntityId("player-$it") }
    val active = players.first()

    fun game(turn: OtariaTurn? = null): GameState {
        val mode = OtariaState(
            players = players.zip(OtariaLocation.entries).associate { (id, location) ->
                id to OtariaPlayerState(location, location.position)
            },
            explorationsUsed = OtariaLocation.entries.associateWith { 0 },
            starPile = OtariaExplorationPile(emptyList()),
            colorPiles = Color.entries.associateWith { OtariaExplorationPile(emptyList()) },
            treasurePile = emptyList(),
            locationPile = emptyList(),
            turn = turn,
        )
        return GameState(
            entities = players.associateWith {
                ComponentContainer.of(PlayerComponent(it.value, 15), LifeTotalComponent(15), ManaPoolComponent())
            },
            turnOrder = players,
            activePlayerId = active,
            turnNumber = 1,
            otaria = mode,
        )
    }

    fun steps(turn: OtariaTurn): List<Step> {
        val state = game(turn)
        val result = mutableListOf(Step.UNTAP)
        while (result.last() != Step.CLEANUP) {
            check(result.size < 20) { "Turn did not end" }
            result += OtariaTurnPolicy.nextStep(state, result.last())
        }
        return result
    }

    test("each chosen action has its specified sequence of steps") {
        val turn = OtariaTurn(active, 1, OtariaTurnAction.MOVE)
        steps(turn) shouldBe listOf(
            Step.UNTAP, Step.UPKEEP, Step.PRECOMBAT_MAIN, Step.OTARIA_MOVEMENT, Step.END, Step.CLEANUP,
        )
        steps(turn.copy(action = OtariaTurnAction.PREPARE)) shouldBe listOf(
            Step.UNTAP, Step.UPKEEP, Step.OTARIA_PREPARATION, Step.DRAW, Step.PRECOMBAT_MAIN, Step.END, Step.CLEANUP,
        )
        steps(turn.copy(action = OtariaTurnAction.EXPLORE, explorationFinished = true)) shouldBe listOf(
            Step.UNTAP, Step.UPKEEP, Step.PRECOMBAT_MAIN, Step.OTARIA_EXPLORATION,
            Step.POSTCOMBAT_MAIN, Step.END, Step.CLEANUP,
        )
        steps(turn.copy(action = OtariaTurnAction.COMBAT)) shouldBe listOf(
            Step.UNTAP, Step.UPKEEP, Step.DRAW, Step.PRECOMBAT_MAIN, Step.BEGIN_COMBAT,
            Step.DECLARE_ATTACKERS, Step.DECLARE_BLOCKERS, Step.FIRST_STRIKE_COMBAT_DAMAGE,
            Step.COMBAT_DAMAGE, Step.END_COMBAT, Step.POSTCOMBAT_MAIN, Step.END, Step.CLEANUP,
        )
    }

    test("failed retreat and unfinished exploration skip their optional phases") {
        steps(OtariaTurn(active, 1, OtariaTurnAction.MOVE, movementAllowed = false)) shouldBe
            listOf(Step.UNTAP, Step.UPKEEP, Step.END, Step.CLEANUP)
        steps(OtariaTurn(active, 1, OtariaTurnAction.EXPLORE)) shouldBe listOf(
            Step.UNTAP, Step.UPKEEP, Step.PRECOMBAT_MAIN, Step.OTARIA_EXPLORATION, Step.END, Step.CLEANUP,
        )
    }

    test("ordinary games and stale selections follow the ordinary turn") {
        val oldTurn = OtariaTurn(active, 1, OtariaTurnAction.MOVE)
        val regular = game().copy(otaria = null)
        val stale = game(oldTurn).copy(turnNumber = 2)
        val otherPlayer = game(oldTurn).copy(activePlayerId = players[1])
        for (state in listOf(regular, stale, otherPlayer)) {
            OtariaTurnPolicy.nextStep(state, Step.UPKEEP) shouldBe Step.DRAW
            OtariaTurnPolicy.nextStep(state, Step.PRECOMBAT_MAIN) shouldBe Step.BEGIN_COMBAT
        }
    }

    test("the player chooses before untapping, and the choice survives serialization") {
        val registry = CardRegistry()
        val forest = CardDefinition.basicLand("Forest", Subtype.FOREST)
        registry.register(forest)
        val land = EntityId("land")
        val initial = game()
            .withEntity(land, CardEntityFactory.create(forest, active).with(TappedComponent))
            .addToZone(ZoneKey(active, Zone.BATTLEFIELD), land)
        val services = EngineServices(registry)
        val started = services.mulliganHandler.startFirstTurn(initial, emptyList(), services.turnManager)
        val decision = started.state.pendingDecision as ChooseOptionDecision
        decision.playerId shouldBe active
        decision.options shouldBe listOf("Move", "Explore", "Prepare", "Combat")
        started.state.getEntity(land)?.has<TappedComponent>() shouldBe true

        val json = Json { serializersModule = engineSerializersModule; allowStructuredMapKeys = true; encodeDefaults = true }
        val restored = json.decodeFromString<ExecutionResult>(json.encodeToString(started))
        restored shouldBe started
        val processor = ActionProcessor(services)
        val invalid = processor.process(restored.state, SubmitDecision(active, OptionChosenResponse(decision.id, 4))).result
        invalid.error shouldNotBe null
        invalid.state shouldBe restored.state
        val wrongPlayer = processor.process(restored.state, SubmitDecision(players[1], OptionChosenResponse(decision.id, 0))).result
        wrongPlayer.error shouldNotBe null
        wrongPlayer.state shouldBe restored.state

        val answered = processor.process(restored.state, SubmitDecision(active, OptionChosenResponse(decision.id, 0))).result
        answered.error shouldBe null
        answered.state.otaria?.turn shouldBe OtariaTurn(active, 1, OtariaTurnAction.MOVE)
        answered.events.filterIsInstance<OtariaTurnActionChosenEvent>() shouldBe
            listOf(OtariaTurnActionChosenEvent(active, OtariaTurnAction.MOVE))
        answered.state.step shouldBe Step.UPKEEP
        answered.state.getEntity(land)?.has<TappedComponent>() shouldBe false

        val main = services.turnManager.advanceStep(answered.state)
        main.state.step shouldBe Step.PRECOMBAT_MAIN
        services.turnManager.advanceStep(main.state).state.step shouldBe Step.OTARIA_MOVEMENT
    }
})
