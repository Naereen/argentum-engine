package com.wingedsheep.engine.otaria

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.view.ClientStateTransformer
import com.wingedsheep.engine.handlers.effects.library.resolveControllerStepTurn
import com.wingedsheep.engine.mechanics.sba.game.GameEndCheck
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.LifeTotalComponent
import com.wingedsheep.engine.state.components.identity.PlayerComponent
import com.wingedsheep.engine.state.components.player.LossReason
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.state.components.player.PlayerLostComponent
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.effects.MayPlayExpiry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class OtariaPhaseActionsTest : FunSpec({
    val players = (1..3).map { EntityId("player-$it") }
    val active = players.first()
    val forest = CardDefinition.basicLand("Forest", Subtype.FOREST)
    val spell = CardDefinition.instant("Otaria Test Spell", ManaCost.parse("{G}"), "")
    val registry = CardRegistry().also {
        it.register(forest)
        it.register(spell)
    }
    val services = EngineServices(registry)
    val processor = ActionProcessor(services)
    val json = Json { serializersModule = engineSerializersModule; allowStructuredMapKeys = true; encodeDefaults = true }

    fun game(action: OtariaTurnAction, step: Step): GameState {
        val mode = OtariaState(
            players = players.zip(OtariaLocation.entries).associate { (id, location) ->
                id to OtariaPlayerState(location, location.position)
            },
            explorationsUsed = OtariaLocation.entries.associateWith { 0 },
            starPile = OtariaExplorationPile(emptyList()),
            colorPiles = Color.entries.associateWith { OtariaExplorationPile(emptyList()) },
            treasurePile = emptyList(),
            locationPile = emptyList(),
            turn = OtariaTurn(active, 1, action),
        )
        return GameState(
            entities = players.associateWith {
                ComponentContainer.of(PlayerComponent(it.value, 15), LifeTotalComponent(15), ManaPoolComponent())
            },
            turnOrder = players,
            activePlayerId = active,
            priorityPlayerId = active,
            turnNumber = 1,
            step = step,
            phase = step.phase,
            otaria = mode,
        )
    }

    fun choose(state: GameState, index: Int): ExecutionResult {
        val decision = state.pendingDecision as ChooseOptionDecision
        return processor.process(state, SubmitDecision(active, OptionChosenResponse(decision.id, index))).result
    }

    test("movement offers only legal destinations and cannot be passed before choosing") {
        val initial = game(OtariaTurnAction.MOVE, Step.PRECOMBAT_MAIN)
        val entered = services.turnManager.advanceStep(initial)
        val decision = entered.state.pendingDecision as ChooseOptionDecision
        entered.state.step shouldBe Step.OTARIA_MOVEMENT
        decision.options.size shouldBe 18
        decision.optionMetadata.size shouldBe decision.options.size
        val continuation = entered.state.peekContinuation() as Suspension
        val destinations = (continuation.answer as OtariaMovementChoiceContinuation).destinations
        val origin = requireNotNull(initial.otaria).players.getValue(active).position
        destinations.all { OtariaBoard.distance(origin, it) in 1L..2L } shouldBe true

        val passed = processor.process(entered.state, PassPriority(active)).result
        passed.error shouldNotBe null
        passed.state shouldBe entered.state
        val restored = json.decodeFromString<ExecutionResult>(json.encodeToString(entered))
        val rejected = choose(restored.state, destinations.size)
        rejected.error shouldNotBe null
        rejected.state shouldBe restored.state
        val moved = choose(restored.state, 0)
        moved.error shouldBe null
        moved.state.pendingDecision shouldBe null
        requireNotNull(moved.state.otaria).players.getValue(active).position shouldBe destinations.first()
        moved.events.filterIsInstance<OtariaPlayerMovedEvent>().size shouldBe 1
        services.turnManager.advanceStep(moved.state).state.step shouldBe Step.END
    }

    test("preparation selects private reserve cards and proceeds to the draw step") {
        val reserveId = EntityId("reserve-spell")
        val libraryId = EntityId("library-spell")
        val initial = game(OtariaTurnAction.PREPARE, Step.UPKEEP)
            .withEntity(reserveId, CardEntityFactory.create(spell, active))
            .withEntity(libraryId, CardEntityFactory.create(spell, active))
            .addToZone(ZoneKey(active, Zone.LIBRARY), libraryId)
        val mode = requireNotNull(initial.otaria)
        val player = mode.players.getValue(active)
        val withReserve = initial.copy(otaria = mode.copy(players = mode.players + (
            active to player.copy(reserve = listOf(reserveId))
        )))
        val entered = services.turnManager.advanceStep(withReserve)
        val decision = entered.state.pendingDecision as SelectCardsDecision
        decision.options shouldBe listOf(reserveId)
        decision.minSelections shouldBe 0
        decision.cardInfo?.get(reserveId)?.name shouldBe spell.name
        decision.cardInfo?.get(reserveId)?.colors shouldBe listOf("GREEN")
        val restored = json.decodeFromString<ExecutionResult>(json.encodeToString(entered))
        val result = processor.process(restored.state, SubmitDecision(
            active, CardsSelectedResponse(decision.id, listOf(reserveId)),
        )).result
        result.error shouldBe null
        requireNotNull(result.state.otaria).players.getValue(active).reserve shouldBe emptyList()
        result.state.getZone(active, Zone.LIBRARY).toSet() shouldBe setOf(libraryId, reserveId)
        result.events.filterIsInstance<OtariaPreparedEvent>().single().playerId shouldBe active
        services.turnManager.advanceStep(result.state).state.step shouldBe Step.DRAW
    }

    test("terrain exploration reveals a noncreature and grants the second main phase") {
        val initial = game(OtariaTurnAction.EXPLORE, Step.PRECOMBAT_MAIN)
        val mode = requireNotNull(initial.otaria)
        val withSpell = initial.copy(otaria = mode.copy(colorPiles = mode.colorPiles + (
            Color.GREEN to OtariaExplorationPile(listOf(OtariaPileCard(spell.name, OtariaCardKind.NONCREATURE)))
        )))
        val entered = services.turnManager.advanceStep(withSpell)
        val decision = entered.state.pendingDecision as ChooseOptionDecision
        decision.options.size shouldBe 4
        decision.options.take(2).all { it.contains("terrain") } shouldBe true
        val resolved = choose(entered.state, 0)
        resolved.error shouldBe null
        val after = requireNotNull(resolved.state.otaria)
        after.turn?.explorationFinished shouldBe true
        after.pendingExploration shouldBe null
        after.explorationsUsed.values.sum() shouldBe 0
        after.players.getValue(active).reserve.map {
            resolved.state.getEntity(it)?.get<CardComponent>()?.name
        } shouldBe listOf("Forest", spell.name)
        val reveal = resolved.events.filterIsInstance<CardsRevealedEvent>().single()
        reveal.revealingPlayerId shouldBe players[1]
        reveal.cardNames shouldBe listOf(spell.name)
        services.turnManager.advanceStep(resolved.state).state.step shouldBe Step.POSTCOMBAT_MAIN
    }

    test("discovery resolves its treasure before a persistent optional location decision") {
        val initial = game(OtariaTurnAction.EXPLORE, Step.PRECOMBAT_MAIN)
        val mode = requireNotNull(initial.otaria)
        val player = mode.players.getValue(active)
        val away = OtariaLocation.ORDER_ENCAMPMENT
        val prepared = initial.copy(otaria = mode.copy(
            players = mode.players + (active to player.copy(position = away.position)),
            starPile = OtariaExplorationPile(listOf(OtariaPileCard(OtariaSetup.DISCOVERY, OtariaCardKind.DISCOVERY))),
            locationPile = listOf(OtariaLocation.CABAL_CITY),
        ))
        val entered = services.turnManager.advanceStep(prepared)
        (entered.state.pendingDecision as ChooseOptionDecision).options.size shouldBe 4
        val discovery = choose(entered.state, 3)
        discovery.error shouldBe null
        val decision = discovery.state.pendingDecision as YesNoDecision
        val during = requireNotNull(discovery.state.otaria)
        during.explorationsUsed.getValue(away) shouldBe 1
        during.pendingExploration?.managerId shouldBe players[1]
        during.pendingExploration?.stage shouldBe OtariaExplorationStage.CHOOSING_LOCATION
        during.turn?.explorationFinished shouldBe false
        val restored = json.decodeFromString<ExecutionResult>(json.encodeToString(discovery))
        val answer = processor.process(restored.state, SubmitDecision(active, YesNoResponse(decision.id, true))).result
        answer.error shouldBe null
        val after = requireNotNull(answer.state.otaria)
        after.players.getValue(active).locationCards shouldBe listOf(OtariaLocation.CABAL_CITY)
        after.pendingExploration shouldBe null
        after.turn?.explorationFinished shouldBe true
        after.starPile.discard.single().kind shouldBe OtariaCardKind.DISCOVERY
    }

    test("a creature exploration enters a real encounter instead of discarding it") {
        val creature = CardDefinition.creature("Otaria Test Creature", ManaCost.ZERO, emptySet(), 2, 2,
            keywords = setOf(com.wingedsheep.sdk.core.Keyword.HASTE))
        registry.register(creature)
        val initial = game(OtariaTurnAction.EXPLORE, Step.PRECOMBAT_MAIN)
        val mode = requireNotNull(initial.otaria)
        val prepared = initial.copy(otaria = mode.copy(colorPiles = mode.colorPiles + (
            Color.GREEN to OtariaExplorationPile(listOf(OtariaPileCard(creature.name, OtariaCardKind.CREATURE)))
        )))
        val entered = services.turnManager.advanceStep(prepared)
        val resolved = choose(entered.state, 1)
        resolved.error shouldBe null
        val after = requireNotNull(resolved.state.otaria)
        after.pendingExploration shouldBe null
        after.turn?.explorationFinished shouldBe true
        after.explorationsUsed.values.sum() shouldBe 0
        after.colorPiles.getValue(Color.GREEN).discard shouldBe emptyList()
        after.players.getValue(active).reserve shouldBe mode.players.getValue(active).reserve
        val encounter = after.encounters.values.single()
        encounter.managerId shouldBe players[1]
        resolved.state.getBattlefield() shouldBe listOf(encounter.primaryId)
        resolved.events.filterIsInstance<OtariaInitiativeDeterminedEvent>().single().plan.first shouldBe
            OtariaInitiativeSide.ENCOUNTER
        services.turnManager.advanceStep(resolved.state).state.step shouldBe Step.END
    }

    test("a player leaving the game takes their reserve with them and the board still projects for the others") {
        val reserveId = EntityId("leaver-reserve-spell")
        val initial = game(OtariaTurnAction.PREPARE, Step.PRECOMBAT_MAIN)
            .withEntity(reserveId, CardEntityFactory.create(spell, players[1]))
        val mode = requireNotNull(initial.otaria)
        val withReserve = initial.copy(otaria = mode.copy(players = mode.players + (
            players[1] to mode.players.getValue(players[1]).copy(reserve = listOf(reserveId))
        )))
        val conceded = processor.process(withReserve, Concede(players[1])).result
        conceded.error shouldBe null
        conceded.state.getEntity(reserveId) shouldBe null
        requireNotNull(conceded.state.otaria).players.getValue(players[1]).reserve shouldBe emptyList()
        val transformer = ClientStateTransformer(registry, predicateEvaluator = services.predicateEvaluator)
        for (viewer in listOf(active, players[2])) {
            requireNotNull(transformer.transform(conceded.state, viewer).otaria)
                .players.single { it.playerId == players[1] }.reserveCount shouldBe 0
        }
    }

    test("a creature missing from the card registry is discarded instead of blocking the turn") {
        val initial = game(OtariaTurnAction.EXPLORE, Step.PRECOMBAT_MAIN)
        val mode = requireNotNull(initial.otaria)
        val prepared = initial.copy(otaria = mode.copy(colorPiles = mode.colorPiles + (
            Color.GREEN to OtariaExplorationPile(listOf(OtariaPileCard("Unscripted Otaria Creature", OtariaCardKind.CREATURE)))
        )))
        val entered = services.turnManager.advanceStep(prepared)
        val resolved = choose(entered.state, 1)
        resolved.error shouldBe null
        val after = requireNotNull(resolved.state.otaria)
        after.pendingExploration shouldBe null
        after.turn?.explorationFinished shouldBe true
        after.encounters shouldBe emptyMap()
        after.colorPiles.getValue(Color.GREEN).discard.single().name shouldBe "Unscripted Otaria Creature"
        resolved.events.filterIsInstance<OtariaCardSkippedEvent>().single().cardName shouldBe "Unscripted Otaria Creature"
        resolved.state.getBattlefield() shouldBe emptyList()
    }

    test("a sole survivor must finish a pending creature encounter before winning") {
        val initial = game(OtariaTurnAction.EXPLORE, Step.OTARIA_EXPLORATION)
            .updateEntity(players[1]) { it.with(PlayerLostComponent(LossReason.CONCESSION)) }
            .updateEntity(players[2]) { it.with(PlayerLostComponent(LossReason.CONCESSION)) }
        val mode = requireNotNull(initial.otaria)
        val pending = OtariaPendingExploration(
            active, players[1], OtariaExplorationSource.Terrain(Color.GREEN),
            OtariaPileCard("Creature", OtariaCardKind.CREATURE),
        )
        val check = GameEndCheck()
        check.check(initial.copy(otaria = mode.copy(pendingExploration = pending))).state.gameOver shouldBe false
        check.check(initial.copy(otaria = mode.copy(
            pendingExploration = pending.copy(card = OtariaPileCard("Boss", OtariaCardKind.BOSS)),
        ))).state.gameOver shouldBe false
        check.check(initial.copy(otaria = mode.copy(
            pendingExploration = pending.copy(card = OtariaPileCard("Spell", OtariaCardKind.NONCREATURE)),
        ))).state.winnerId shouldBe active
        val encounter = OtariaEncounter(
            explorerId = active,
            managerId = players[1],
            position = mode.players.getValue(active).position,
            source = pending.source,
            primaryId = EntityId("encounter-creature"),
        )
        val definition = CardDefinition.creature("Surviving encounter", ManaCost.ZERO, emptySet(), 1, 1)
        val staged = initial.withEntity(encounter.primaryId, CardEntityFactory.create(definition, EntityId("encounter")))
            .addToZone(ZoneKey(EntityId("encounter"), Zone.BATTLEFIELD), encounter.primaryId)
        val live = encounter.copy(members = setOf(requireNotNull(staged.objectRef(encounter.primaryId))))
        val withEncounter = staged.copy(otaria = mode.copy(encounters = mapOf(EntityId("encounter") to live)))
        check.check(withEncounter).state.gameOver shouldBe false
        check.check(withEncounter.copy(otaria = requireNotNull(withEncounter.otaria).copy(
            encounters = mapOf(EntityId("encounter") to live.copy(participants = setOf(players[1]))),
        ))).state.winnerId shouldBe active
        check.check(initial).state.winnerId shouldBe active
    }

    test("Otaria phases compare at their actual point in the turn") {
        (Step.UPKEEP.turnOrderIndex < Step.OTARIA_PREPARATION.turnOrderIndex) shouldBe true
        (Step.OTARIA_PREPARATION.turnOrderIndex < Step.DRAW.turnOrderIndex) shouldBe true
        (Step.PRECOMBAT_MAIN.turnOrderIndex < Step.OTARIA_MOVEMENT.turnOrderIndex) shouldBe true
        (Step.OTARIA_MOVEMENT.turnOrderIndex < Step.END.turnOrderIndex) shouldBe true
        (Step.OTARIA_EXPLORATION.turnOrderIndex < Step.POSTCOMBAT_MAIN.turnOrderIndex) shouldBe true
        for (step in listOf(Step.OTARIA_PREPARATION, Step.OTARIA_MOVEMENT, Step.OTARIA_EXPLORATION)) {
            val state = game(OtariaTurnAction.MOVE, step)
            resolveControllerStepTurn(state, active, MayPlayExpiry.UntilControllerStep(Step.END)) shouldBe 1
        }
    }
})
