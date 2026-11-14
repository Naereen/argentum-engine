package com.wingedsheep.engine.otaria

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.*
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.state.components.battlefield.PhasedOutComponent
import com.wingedsheep.engine.view.ClientStateTransformer
import com.wingedsheep.engine.view.ClientEvent
import com.wingedsheep.engine.view.ClientEventTransformer
import com.wingedsheep.sdk.core.*
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.model.GameRng
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import com.wingedsheep.sdk.scripting.KeywordAbility
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class OtariaEncounterFlowTest : FunSpec({
    val explorer = EntityId("explorer")
    val manager = EntityId("manager")
    val other = EntityId("other")
    val players = listOf(explorer, manager, other)
    val creature = CardDefinition.creature("Encounter creature", ManaCost.ZERO, emptySet(), 1, 2)
    val haste = CardDefinition.creature("Hasty boss", ManaCost.ZERO, emptySet(), 3, 3, keywords = setOf(Keyword.HASTE))
    val attacker = CardDefinition.creature("Explorer army", ManaCost.ZERO, emptySet(), 4, 8)
    val forest = CardDefinition.basicLand("Forest", Subtype.FOREST)
    val morph = creature.copy(
        name = "Hidden encounter", keywordAbilities = listOf(KeywordAbility.Morph(ManaCost.parse("{G}"))),
    )
    val registry = CardRegistry().also { listOf(creature, haste, attacker, forest, morph).forEach(it::register) }
    val services = EngineServices(registry)
    val processor = ActionProcessor(services)
    val json = Json { serializersModule = engineSerializersModule; allowStructuredMapKeys = true }

    fun seed(wins: Boolean): Long = (0L..100L).first {
        OtariaContestPlanner.retreat(GameRng.seeded(it), listOf(manager)).first.succeeds == wins
    }

    fun initial(definition: CardDefinition = creature, boss: Boolean = false, wins: Boolean = true): GameState {
        val location = OtariaLocation.KROSIAN_FOREST
        val card = OtariaPileCard(definition.name, if (boss) OtariaCardKind.BOSS else OtariaCardKind.CREATURE)
        val mode = OtariaState(
            players = players.zip(OtariaLocation.entries).associate { (id, start) ->
                id to OtariaPlayerState(start, if (id == explorer) location.position else start.position,
                    hasExploredLocationAwayFromStart = true)
            },
            explorationsUsed = OtariaLocation.entries.associateWith { 0 },
            starPile = OtariaExplorationPile(if (boss) listOf(card) else emptyList()),
            colorPiles = Color.entries.associateWith {
                OtariaExplorationPile(if (!boss && it == Color.GREEN) listOf(card) else emptyList())
            },
            treasurePile = emptyList(),
            locationPile = emptyList(),
            turn = OtariaTurn(explorer, 1, OtariaTurnAction.EXPLORE),
        )
        return GameState(
            entities = players.associateWith {
                ComponentContainer.of(PlayerComponent(it.value, 15), LifeTotalComponent(15), ManaPoolComponent())
            },
            turnOrder = players,
            activePlayerId = explorer,
            priorityPlayerId = explorer,
            turnNumber = 1,
            step = Step.PRECOMBAT_MAIN,
            phase = Phase.PRECOMBAT_MAIN,
            rng = GameRng.seeded(seed(wins)),
            otaria = mode,
        )
    }

    fun answer(state: GameState, index: Int): ExecutionResult {
        val choice = state.pendingDecision as ChooseOptionDecision
        return processor.process(state, SubmitDecision(choice.playerId, OptionChosenResponse(choice.id, index))).result
    }

    fun explore(state: GameState, boss: Boolean = false): ExecutionResult {
        val entered = services.turnManager.advanceStep(state)
        entered.error shouldBe null
        return answer(entered.state, if (boss) 3 else 1)
    }

    fun actor(state: GameState) = requireNotNull(state.otaria).encounters.keys.single()
    fun encounter(state: GameState) = requireNotNull(state.otaria).encounters.values.single()

    test("terrain creatures enter under the left manager and initiative resumes after serialization") {
        val before = initial()
        val result = explore(before)
        result.error shouldBe null
        val encounter = encounter(result.state)
        encounter.managerId shouldBe manager
        result.state.actorFor(actor(result.state)) shouldBe manager
        result.state.projectedState.getController(encounter.primaryId) shouldBe actor(result.state)
        result.state.otaria?.pendingExploration shouldBe null
        result.state.otaria?.colorPiles?.get(Color.GREEN)?.cards shouldBe emptyList()
        val restored = json.decodeFromString<ExecutionResult>(json.encodeToString(result))
        val fought = answer(restored.state, 0)
        fought.error shouldBe null
        services.turnManager.advanceStep(fought.state).state.step shouldBe Step.BEGIN_COMBAT
        before.otaria?.encounters shouldBe emptyMap()
        before.otaria?.colorPiles?.get(Color.GREEN)?.cards?.size shouldBe 1
    }

    test("a boss uses the same encounter flow and consumes one location exploration") {
        val result = explore(initial(boss = true), boss = true)
        result.error shouldBe null
        encounter(result.state).pileCard?.kind shouldBe OtariaCardKind.BOSS
        result.state.otaria?.explorationsUsed?.get(OtariaLocation.KROSIAN_FOREST) shouldBe 1
        answer(result.state, 0).state.otaria?.turn?.encounterAction shouldBe OtariaTurnAction.COMBAT
    }

    test("public entry keeps morph private and initiative waits for serialized encounter mana choices") {
        val land = EntityId("forest")
        val before = initial(morph).withEntity(land, CardEntityFactory.create(forest, explorer))
            .addToZone(ZoneKey(explorer, Zone.BATTLEFIELD), land)
        val entered = explore(before)
        val question = entered.pendingDecision as ChooseColorDecision
        val actorId = actor(entered.state)
        question.playerId shouldBe actorId
        entered.state.actorFor(question.playerId) shouldBe manager
        entered.state.otaria?.turn?.encounterInitiative shouldBe null
        val primary = encounter(entered.state).primaryId
        entered.state.getEntity(primary)?.has<FaceDownComponent>() shouldBe true
        val transformer = ClientStateTransformer(registry, predicateEvaluator = services.predicateEvaluator)
        transformer.transform(entered.state, explorer).cards.getValue(primary).name shouldNotBe morph.name
        transformer.transform(entered.state, manager).cards.getValue(primary).name shouldBe morph.name
        val restored = json.decodeFromString<ExecutionResult>(json.encodeToString(entered))
        val assigned = processor.process(restored.state, SubmitDecision(
            actorId, ColorChosenResponse(question.id, Color.GREEN),
        )).result
        assigned.error shouldBe null
        assigned.events.filterIsInstance<OtariaInitiativeDeterminedEvent>().size shouldBe 1
        assigned.state.otaria?.turn?.encounterInitiative shouldBe OtariaInitiativeSide.EXPLORER
        answer(assigned.state, 0).state.otaria?.turn?.encounterAction shouldBe OtariaTurnAction.COMBAT
    }

    test("initiative results are structured and visible to every player") {
        val result = explore(initial())
        val event = result.events.filterIsInstance<OtariaInitiativeDeterminedEvent>().single()
        val explorerView = ClientEventTransformer.transform(listOf(event), explorer).single() as ClientEvent.OtariaNotice
        val opponentView = ClientEventTransformer.transform(listOf(event), other).single() as ClientEvent.OtariaNotice

        explorerView.initiativeOutcome shouldBe "explorerWon"
        explorerView.explorerId shouldBe explorer
        explorerView.initiativeRolls shouldBe event.plan.contest?.rounds?.joinToString("; ") { "${it.explorer} vs ${it.opponent}" }
        opponentView.initiativeOutcome shouldBe explorerView.initiativeOutcome
        opponentView.explorerId shouldBe explorerView.explorerId
        opponentView.description shouldBe explorerView.description
    }

    test("losing initiative ends the explorer turn and the encounter acts before the next seat") {
        val result = explore(initial(wins = false))
        result.pendingDecision shouldBe null
        val ended = services.turnManager.advanceStep(result.state)
        ended.state.step shouldBe Step.END
        var current = ended.state
        repeat(3) {
            if (current.activePlayerId == explorer) {
                val next = services.settler.settle(services.turnManager.advanceStep(current))
                next.error shouldBe null
                current = next.state
            }
        }
        current.activePlayerId shouldBe actor(result.state)
    }

    test("haste wins initiative without rolling and never offers the free retreat choice") {
        val before = initial(haste, boss = true)
        val result = explore(before, boss = true)
        result.error shouldBe null
        result.pendingDecision shouldBe null
        val plan = result.events.filterIsInstance<OtariaInitiativeDeterminedEvent>().single().plan
        plan.first shouldBe OtariaInitiativeSide.ENCOUNTER
        plan.contest shouldBe null
        result.state.rng shouldBe before.rng
        services.turnManager.advanceStep(result.state).state.step shouldBe Step.END
    }

    test("winning initiative permits a free retreat and returns a boss without restoring its quota") {
        val result = explore(initial(boss = true), boss = true)
        val fleeing = answer(result.state, 1)
        val movement = services.turnManager.advanceStep(fleeing.state)
        movement.state.step shouldBe Step.OTARIA_MOVEMENT
        val rng = movement.state.rng
        val moved = answer(movement.state, 0)
        moved.error shouldBe null
        moved.events.filterIsInstance<OtariaRetreatDeterminedEvent>().single().plan.contests shouldBe emptyList()
        moved.state.rng shouldBe rng
        moved.state.otaria?.encounters shouldBe emptyMap()
        moved.state.otaria?.starPile?.cards?.single()?.kind shouldBe OtariaCardKind.BOSS
        moved.state.otaria?.explorationsUsed?.get(OtariaLocation.KROSIAN_FOREST) shouldBe 1
        moved.state.getBattlefield() shouldBe emptyList()
        services.turnManager.advanceStep(moved.state).state.step shouldBe Step.END
    }

    test("a later failed retreat keeps the encounter and boss quota unchanged") {
        val entered = explore(initial(haste, boss = true), boss = true).state
        val mode = requireNotNull(entered.otaria)
        val moving = entered.copy(
            step = Step.OTARIA_MOVEMENT,
            phase = Phase.OTARIA_MOVEMENT,
            rng = GameRng.seeded(seed(false)),
            otaria = mode.copy(turn = OtariaTurn(explorer, entered.turnNumber, OtariaTurnAction.MOVE)),
        )
        val choice = services.turnManager.otariaPhaseActions.begin(moving, explorer)
        val failed = answer(choice.state, 0)
        failed.error shouldBe null
        failed.state.otaria?.players?.get(explorer)?.position shouldBe mode.players.getValue(explorer).position
        failed.state.otaria?.encounters?.size shouldBe 1
        failed.state.otaria?.turn?.movementAllowed shouldBe false
        failed.state.otaria?.explorationsUsed shouldBe mode.explorationsUsed
    }

    test("a phased-out encounter cannot oppose retreat but still returns when abandoned") {
        val entered = explore(initial(haste)).state
        val primary = encounter(entered).primaryId
        val mode = requireNotNull(entered.otaria)
        val moving = entered.updateEntity(primary) { it.with(PhasedOutComponent(actor(entered))) }.copy(
            step = Step.OTARIA_MOVEMENT, phase = Phase.OTARIA_MOVEMENT,
            otaria = mode.copy(turn = OtariaTurn(explorer, entered.turnNumber, OtariaTurnAction.MOVE)),
        )
        val offered = services.turnManager.otariaPhaseActions.begin(moving, explorer)
        val left = answer(offered.state, 0)
        left.error shouldBe null
        left.events.filterIsInstance<OtariaRetreatDeterminedEvent>().single().plan.contests shouldBe emptyList()
        left.state.rng shouldBe moving.rng
        left.state.otaria?.encounters shouldBe emptyMap()
        left.state.otaria?.colorPiles?.get(Color.GREEN)?.cards?.single()?.name shouldBe haste.name
    }

    test("lethal combat damage captures the encountered card into the attacking player's reserve") {
        val own = EntityId("army")
        val before = initial().withEntity(own, CardEntityFactory.create(attacker, explorer))
            .addToZone(ZoneKey(explorer, Zone.BATTLEFIELD), own)
        val entered = explore(before)
        val fought = answer(entered.state, 0)
        val combat = services.turnManager.advanceStep(fought.state)
        val declare = services.turnManager.advanceStep(combat.state)
        val primary = encounter(declare.state).primaryId
        val declared = processor.process(declare.state, DeclareAttackers(explorer, mapOf(own to primary))).result
        declared.error shouldBe null
        val damage = services.combatManager.applyCombatDamage(declared.state)
        val settled = services.settler.settle(damage)
        settled.error shouldBe null
        settled.state.otaria?.encounters shouldBe emptyMap()
        val captured = requireNotNull(settled.state.otaria).players.getValue(explorer).reserve.single()
        settled.state.getEntity(captured)?.get<CardComponent>()?.name shouldBe creature.name
        settled.state.getEntity(captured)?.get<OwnerComponent>()?.playerId shouldBe explorer
        settled.state.getZone(actor(declare.state), Zone.GRAVEYARD) shouldBe emptyList()
        settled.events.filterIsInstance<OtariaCreatureCapturedEvent>().single().playerId shouldBe explorer
    }

    test("a removal spell credits its controller, not the explorer who drew the creature") {
        val entered = explore(initial(haste)).state
        val primary = encounter(entered).primaryId
        val destroyed = services.effectExecutorRegistry.execute(
            entered, Effects.Destroy(EffectTarget.SpecificEntity(primary)),
            EffectContext(sourceId = EntityId("removal"), controllerId = other),
        )
        destroyed.error shouldBe null
        val mode = requireNotNull(destroyed.state.otaria)
        mode.players.getValue(other).reserve.size shouldBe 1
        mode.players.getValue(explorer).reserve shouldBe emptyList()
    }

    test("bouncing the primary creature shuffles it back rather than capturing or duplicating it") {
        val entered = explore(initial(haste)).state
        val primary = encounter(entered).primaryId
        val bounced = services.zones.moveToZone(entered, primary, Zone.HAND)
        val cleared = services.settler.settle(ExecutionResult.success(bounced.state, bounced.events))
        cleared.state.otaria?.encounters shouldBe emptyMap()
        cleared.state.otaria?.colorPiles?.get(Color.GREEN)?.cards?.single()?.name shouldBe haste.name
        cleared.state.otaria?.players?.get(explorer)?.reserve shouldBe emptyList()
        cleared.state.getEntity(primary) shouldBe null
    }

    test("departures outside effect executors also capture once at the settlement boundary") {
        val entered = explore(initial(haste)).state
        val primary = encounter(entered).primaryId
        val departed = services.zones.moveToZone(entered, primary, Zone.GRAVEYARD)
        val settled = services.settler.settle(ExecutionResult.success(departed.state, departed.events))
        settled.error shouldBe null
        settled.state.otaria?.players?.get(explorer)?.reserve?.size shouldBe 1
        settled.state.otaria?.encounters shouldBe emptyMap()
        settled.events.filterIsInstance<OtariaCreatureCapturedEvent>().size shouldBe 1
        val again = services.settler.settle(settled)
        again.state.otaria?.players?.get(explorer)?.reserve?.size shouldBe 1
    }

    test("defeated reinforcement tokens are removed without adding a reserve card") {
        val before = initial()
        val support = OtariaEncounterSupport(reinforcements = listOf(OtariaReinforcementGroup(
            OtariaReinforcementContent.CreatureToken(setOf(Color.GREEN), setOf(Subtype.ELF), 1, 1),
            OtariaReinforcementCount.Fixed(1),
        )))
        val mode = requireNotNull(before.otaria)
        val entered = explore(before.copy(otaria = mode.copy(
            encounterSupport = OtariaEncounterSupportTable(colors = mapOf(Color.GREEN to mapOf(creature.name to support))),
        )))
        val fought = answer(entered.state, 0).state
        val encounter = encounter(fought)
        val token = encounter.members.single { it.entityId != encounter.primaryId }.entityId
        val destroyed = services.effectExecutorRegistry.execute(
            fought, Effects.Destroy(EffectTarget.SpecificEntity(token)),
            EffectContext(sourceId = EntityId("removal"), controllerId = explorer),
        )
        val settled = services.settler.settle(destroyed.toExecutionResult())
        settled.state.otaria?.players?.get(explorer)?.reserve shouldBe emptyList()
        settled.state.otaria?.encounters?.size shouldBe 1
        settled.events.filterIsInstance<OtariaCreatureCapturedEvent>() shouldBe emptyList()
        settled.state.getEntity(token) shouldBe null
    }

    test("permanent exile removes the creature without capture or a return to the pile") {
        val entered = explore(initial(haste)).state
        val exiled = services.zones.moveToZone(entered, encounter(entered).primaryId, Zone.EXILE)
        val resolved = OtariaEncounterLifecycle.departures(exiled.state, exiled.events, registry, explorer)
        val cleared = OtariaEncounterLifecycle.finishEmpty(resolved.state, services.zones)
        cleared.state.otaria?.encounters shouldBe emptyMap()
        cleared.state.otaria?.colorPiles?.get(Color.GREEN)?.cards shouldBe emptyList()
        cleared.state.otaria?.players?.get(explorer)?.reserve shouldBe emptyList()
    }
})
