package com.wingedsheep.engine.otaria

import com.wingedsheep.engine.core.ActionProcessor
import com.wingedsheep.engine.core.CardEntityFactory
import com.wingedsheep.engine.core.CombatResolutionDecision
import com.wingedsheep.engine.core.CombatResolutionResponse
import com.wingedsheep.engine.core.DamageDealtEvent
import com.wingedsheep.engine.core.DamageEdgeAmount
import com.wingedsheep.engine.core.DamageEdgeDirection
import com.wingedsheep.engine.core.DeclareAttackers
import com.wingedsheep.engine.core.DistributeDecision
import com.wingedsheep.engine.core.DistributionResponse
import com.wingedsheep.engine.core.EngineServices
import com.wingedsheep.engine.core.ExecutionResult
import com.wingedsheep.engine.core.OtariaEncounterJoinedEvent
import com.wingedsheep.engine.core.ResolutionTargetKind
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.engine.core.tap
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.mechanics.combat.CombatDefenders
import com.wingedsheep.engine.mechanics.layers.StaticAbilityHandler
import com.wingedsheep.engine.mechanics.layers.ContinuousEffectSourceComponent
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.CountersComponent
import com.wingedsheep.engine.state.components.battlefield.DamageComponent
import com.wingedsheep.engine.state.components.battlefield.HasBecomeTappedComponent
import com.wingedsheep.engine.state.components.battlefield.TappedComponent
import com.wingedsheep.engine.state.components.combat.BeingAttackedComponent
import com.wingedsheep.engine.state.components.identity.LifeTotalComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.identity.FaceDownComponent
import com.wingedsheep.engine.state.components.identity.FaceDownModeComponent
import com.wingedsheep.engine.state.components.identity.PlayerComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.handlers.PredicateEvaluator
import com.wingedsheep.engine.view.ClientCombatTarget
import com.wingedsheep.engine.view.ClientStateTransformer
import com.wingedsheep.engine.view.DecisionMasker
import com.wingedsheep.engine.view.Visibility
import com.wingedsheep.engine.state.FACE_DOWN_DISPLAY_NAME
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.CounterType
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.dsl.Conditions
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.scripting.CantBlock
import com.wingedsheep.sdk.scripting.CantBlockUnless
import com.wingedsheep.sdk.scripting.CantBlockUnlessCoBlocker
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.UntapLimitPerStep
import com.wingedsheep.sdk.scripting.effects.FaceDownMode
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class OtariaEncounterCombatTest : FunSpec({
    val explorer = EntityId("explorer")
    val manager = EntityId("manager")
    val other = EntityId("other")
    val actor = EntityId("encounter")
    val defender = EntityId("defender")
    val support = EntityId("support")
    val first = EntityId("attacker-one")
    val second = EntityId("attacker-two")
    val players = listOf(explorer, manager, other)

    fun creature(name: String, power: Int, toughness: Int, vararg keywords: Keyword) =
        CardDefinition.creature(name, ManaCost.ZERO, setOf(Subtype.HUMAN), power, toughness, keywords = keywords.toSet())

    val ground = creature("Ground attacker", 2, 6)
    val reach = creature("Reach attacker", 2, 6, Keyword.REACH)
    val trample = creature("Trampling attacker", 4, 8, Keyword.TRAMPLE)
    val plainDefender = creature("Encounter defender", 3, 7)
    val flyingDefender = creature("Flying encounter", 3, 7, Keyword.FLYING)
    val firstStrikeDefender = creature("First strike encounter", 3, 7, Keyword.FIRST_STRIKE)
    val doubleStrikeDefender = creature("Double strike encounter", 3, 7, Keyword.DOUBLE_STRIKE)
    val blocker = creature("Encounter reinforcement", 1, 1)
    val unableToBlock = card("Unable to block encounter") {
        typeLine = "Creature"; power = 3; toughness = 7
        staticAbility { ability = CantBlock() }
    }
    val unableWhileTapped = card("Encounter unable to block while tapped") {
        typeLine = "Creature"; power = 3; toughness = 7
        staticAbility { ability = CantBlockUnless(Conditions.SourceMatches(GameObjectFilter.Any.untapped())) }
    }
    val needsCoBlocker = card("Encounter needing a co-blocker") {
        typeLine = "Creature"; power = 3; toughness = 7
        staticAbility { ability = CantBlockUnlessCoBlocker(GameObjectFilter.Creature) }
    }
    val noCreatureUntaps = card("No creature untaps") {
        typeLine = "Artifact"
        staticAbility { ability = UntapLimitPerStep(GameObjectFilter.Creature, 0) }
    }
    val registry = CardRegistry().also {
        listOf(ground, reach, trample, plainDefender, flyingDefender, firstStrikeDefender,
            doubleStrikeDefender, blocker, noCreatureUntaps, unableToBlock, unableWhileTapped,
            needsCoBlocker).forEach(it::register)
    }
    val services = EngineServices(registry)
    val staticAbilityHandler = StaticAbilityHandler(registry)
    val processor = ActionProcessor(services)
    val json = Json { serializersModule = engineSerializersModule; allowStructuredMapKeys = true }

    fun state(
        attacker: CardDefinition = ground,
        secondAttacker: CardDefinition? = null,
        encountered: CardDefinition = plainDefender,
        withSupport: Boolean = false,
    ): GameState {
        val locations = listOf(OtariaLocation.KROSIAN_FOREST, OtariaLocation.ORDER_ENCAMPMENT, OtariaLocation.BALSHAN_BAY)
        val positions = listOf(OtariaPosition(6, 5), OtariaPosition(7, 5), OtariaPosition(10, 11))
        val mode = OtariaState(
            players = players.indices.associate { index ->
                players[index] to OtariaPlayerState(locations[index], positions[index])
            },
            explorationsUsed = OtariaLocation.entries.associateWith { 0 },
            starPile = OtariaExplorationPile(emptyList()),
            colorPiles = Color.entries.associateWith { OtariaExplorationPile(emptyList()) },
            treasurePile = emptyList(),
            locationPile = emptyList(),
            turn = OtariaTurn(explorer, 1, OtariaTurnAction.COMBAT),
        )
        val humanEntities = players.associateWith {
            ComponentContainer.of(PlayerComponent(it.value, 15), LifeTotalComponent(15), ManaPoolComponent())
        }
        var result = GameState(
            entities = humanEntities + (actor to ComponentContainer.of(PlayerComponent("Encounter"), ManaPoolComponent())),
            turnOrder = players,
            activePlayerId = explorer,
            priorityPlayerId = explorer,
            turnNumber = 1,
            step = Step.DECLARE_ATTACKERS,
            phase = Step.DECLARE_ATTACKERS.phase,
            otaria = mode,
        )
        fun add(id: EntityId, owner: EntityId, definition: CardDefinition) {
            val entity = staticAbilityHandler.addContinuousEffectComponent(
                CardEntityFactory.create(definition, owner), definition,
            )
            result = result.withEntity(id, entity)
                .addToZone(ZoneKey(owner, Zone.BATTLEFIELD), id)
        }
        add(first, explorer, attacker)
        if (secondAttacker != null) add(second, explorer, secondAttacker)
        add(defender, actor, encountered)
        if (withSupport) add(support, actor, blocker)
        val members = listOfNotNull(result.objectRef(defender), if (withSupport) result.objectRef(support) else null).toSet()
        val encounter = OtariaEncounter(
            explorerId = explorer,
            managerId = manager,
            position = positions.first(),
            source = OtariaExplorationSource.Terrain(Color.GREEN),
            primaryId = defender,
            members = members,
        )
        return result.copy(otaria = mode.copy(encounters = mapOf(actor to encounter)))
    }

    fun declare(state: GameState, attackers: List<EntityId> = listOf(first)): GameState {
        val result = processor.process(state, DeclareAttackers(explorer, attackers.associateWith { defender })).result
        result.error shouldBe null
        return result.state
    }

    fun damage(state: GameState, id: EntityId): Int =
        state.getEntity(id)?.get<DamageComponent>()?.amount ?: 0

    test("flying encounter targets require flying or reach and never expose the manager as the defender") {
        val initial = state(attacker = ground, secondAttacker = reach, encountered = flyingDefender)
        val targets = CombatDefenders.attackTargets(initial, initial.projectedState, explorer)
        (defender in targets) shouldBe true
        (actor in targets) shouldBe false
        (manager in targets) shouldBe false
        val offered = services.legalActionEnumerator.enumerate(initial, explorer).single { it.actionType == "DeclareAttackers" }
        (defender in requireNotNull(offered.validAttackTargets)) shouldBe true
        offered.validAttackers shouldBe listOf(second)
        processor.process(initial, DeclareAttackers(explorer, mapOf(first to defender))).result.error shouldContain "flying or reach"
        processor.process(initial, DeclareAttackers(explorer, mapOf(first to manager))).result.error shouldNotBe null
        val declared = declare(initial, listOf(second))
        declared.getEntity(defender)?.has<BeingAttackedComponent>() shouldBe true
        CombatDefenders.defendingPlayers(declared) shouldBe setOf(actor)
        services.turnManager.getValidBlockers(declared, actor).contains(defender) shouldBe false
        services.combatManager.declareBlockers(declared, actor, mapOf(defender to listOf(second))).error shouldContain "cannot also block"
    }

    test("an unblocked attacker and encounter creature deal combat damage simultaneously") {
        val declared = declare(state())
        val result = services.combatManager.applyCombatDamage(declared)
        result.error shouldBe null
        damage(result.state, first) shouldBe 3
        damage(result.state, defender) shouldBe 2
        result.events.filterIsInstance<DamageDealtEvent>().map { it.sourceId to it.targetId }.toSet() shouldBe
            setOf(first to defender, defender to first)
    }

    test("an encounter defender's first strike creates the first strike step and does not repeat") {
        val declared = declare(state(encountered = firstStrikeDefender))
        val blockersStep = declared.copy(step = Step.DECLARE_BLOCKERS, phase = Step.DECLARE_BLOCKERS.phase)
        val firstStep = services.turnManager.advanceStep(blockersStep)
        firstStep.error shouldBe null
        firstStep.state.step shouldBe Step.FIRST_STRIKE_COMBAT_DAMAGE
        damage(firstStep.state, first) shouldBe 3
        damage(firstStep.state, defender) shouldBe 0
        val regular = services.turnManager.advanceStep(firstStep.state)
        regular.error shouldBe null
        regular.state.step shouldBe Step.COMBAT_DAMAGE
        damage(regular.state, first) shouldBe 3
        damage(regular.state, defender) shouldBe 2
    }

    test("an encounter defender with double strike retaliates in both real damage steps") {
        val declared = declare(state(encountered = doubleStrikeDefender))
        val blockersStep = declared.copy(step = Step.DECLARE_BLOCKERS, phase = Step.DECLARE_BLOCKERS.phase)
        val firstStep = services.turnManager.advanceStep(blockersStep)
        firstStep.error shouldBe null
        firstStep.state.step shouldBe Step.FIRST_STRIKE_COMBAT_DAMAGE
        damage(firstStep.state, first) shouldBe 3
        val regular = services.turnManager.advanceStep(firstStep.state)
        regular.error shouldBe null
        regular.state.step shouldBe Step.COMBAT_DAMAGE
        regular.state.getZone(explorer, Zone.GRAVEYARD) shouldBe listOf(first)
        (firstStep.events + regular.events).filterIsInstance<DamageDealtEvent>()
            .filter { it.sourceId == defender && it.targetId == first }
            .map { it.amount } shouldBe listOf(3, 3)
        damage(regular.state, defender) shouldBe 2
    }

    test("a normal blocker shields the encounter creature and prevents its retaliation") {
        val declared = declare(state(withSupport = true))
        val blocked = services.combatManager.declareBlockers(declared, actor, mapOf(support to listOf(first)))
        blocked.error shouldBe null
        val result = services.combatManager.applyCombatDamage(blocked.state)
        result.error shouldBe null
        damage(result.state, first) shouldBe 1
        damage(result.state, support) shouldBe 2
        damage(result.state, defender) shouldBe 0
    }

    test("trample reaches the encounter defender and it retaliates even through a blocker") {
        val declared = declare(state(attacker = trample, withSupport = true))
        val blocked = services.combatManager.declareBlockers(declared, actor, mapOf(support to listOf(first)))
        blocked.error shouldBe null
        val pending = services.combatManager.applyCombatDamage(blocked.state)
        val question = pending.state.pendingDecision as CombatResolutionDecision
        question.defenders.single { it.id == defender }.kind shouldBe ResolutionTargetKind.CREATURE
        val drain = question.edges.single { it.targetId == defender }
        drain.direction shouldBe DamageEdgeDirection.ATTACKER_TO_CREATURE
        val values = question.edges.filter { it.editableBy == explorer }.map {
            DamageEdgeAmount(it.id, if (it.targetId == support) 1 else if (it.targetId == defender) 3 else it.amount)
        }
        val answered = processor.process(pending.state, SubmitDecision(explorer, CombatResolutionResponse(question.id, values))).result
        answered.error shouldBe null
        damage(answered.state, first) shouldBe 4
        damage(answered.state, defender) shouldBe 3
        answered.events.filterIsInstance<DamageDealtEvent>().count { it.sourceId == defender && it.targetId == first } shouldBe 1
    }

    test("the manager divides retaliation between multiple attackers and the choice survives serialization") {
        val declared = declare(state(secondAttacker = ground), listOf(first, second))
        val pending = services.combatManager.applyCombatDamage(declared)
        val question = pending.state.pendingDecision as DistributeDecision
        question.playerId shouldBe actor
        question.targets.toSet() shouldBe setOf(first, second)
        question.totalAmount shouldBe 3
        val restored = json.decodeFromString<GameState>(json.encodeToString(pending.state))
        val bad = processor.process(restored, SubmitDecision(actor, DistributionResponse(question.id, mapOf(first to 4)))).result
        bad.error shouldNotBe null
        bad.state shouldBe restored
        val wrongSeat = processor.process(restored, SubmitDecision(manager, DistributionResponse(question.id, mapOf(first to 3)))).result
        wrongSeat.error shouldNotBe null
        val answered = processor.process(
            restored, SubmitDecision(actor, DistributionResponse(question.id, mapOf(first to 1, second to 2))),
        ).result
        answered.error shouldBe null
        damage(answered.state, first) shouldBe 1
        damage(answered.state, second) shouldBe 2
        damage(answered.state, defender) shouldBe 4
        answered.state.priorityPlayerId shouldBe explorer
    }

    test("tapping or a stun counter alone does not prevent retaliation") {
        val declared = declare(state())
        val newlyTapped = tap(declared, defender).state
        val recent = services.combatManager.applyCombatDamage(newlyTapped)
        damage(recent.state, defender) shouldBe 2
        damage(recent.state, first) shouldBe 3

        val oldTap = declared.updateEntity(defender) {
            it.with(TappedComponent).with(HasBecomeTappedComponent(lastBecameTappedTurn = 0, timesThisTurn = 1))
        }
        damage(services.combatManager.applyCombatDamage(oldTap).state, first) shouldBe 3
        val stunned = oldTap.updateEntity(defender) {
            it.with(CountersComponent().withAdded(CounterType.STUN, 1))
        }
        damage(services.combatManager.applyCombatDamage(stunned).state, first) shouldBe 3
    }

    test("an untap restriction alone does not prevent retaliation") {
        val capId = EntityId("untap-cap")
        val initial = state()
            .withEntity(capId, CardEntityFactory.create(noCreatureUntaps, explorer))
            .addToZone(ZoneKey(explorer, Zone.BATTLEFIELD), capId)
        val declared = declare(initial).updateEntity(defender) {
            it.with(TappedComponent).with(HasBecomeTappedComponent(lastBecameTappedTurn = 0, timesThisTurn = 1))
        }
        val result = services.combatManager.applyCombatDamage(declared)
        result.error shouldBe null
        damage(result.state, first) shouldBe 3
        damage(result.state, defender) shouldBe 2
    }

    test("an encounter creature that cannot block does not retaliate or ask to divide damage") {
        val declared = declare(state(encountered = unableToBlock, secondAttacker = ground), listOf(first, second))
        declared.projectedState.cantBlock(defender) shouldBe true
        val result = services.combatManager.applyCombatDamage(declared)
        result.error shouldBe null
        result.state.pendingDecision shouldBe null
        damage(result.state, first) shouldBe 0
        damage(result.state, second) shouldBe 0
        damage(result.state, defender) shouldBe 4
        result.events.filterIsInstance<DamageDealtEvent>().any { it.sourceId == defender } shouldBe false
    }

    test("a temporary can't-block effect suppresses retaliation even when the creature is tapped") {
        val declared = declare(state())
        val effect = services.effectExecutorRegistry.execute(
            declared,
            Effects.CantBlock(EffectTarget.ContextTarget(0)),
            EffectContext(sourceId = first, controllerId = explorer, targets = listOf(ChosenTarget.Permanent(defender))),
        )
        effect.error shouldBe null
        val result = services.combatManager.applyCombatDamage(tap(effect.state, defender).state)
        damage(result.state, first) shouldBe 0
        damage(result.state, defender) shouldBe 2
    }

    test("a restriction specifically preventing blocking while tapped suppresses retaliation only while tapped") {
        val declared = declare(state(encountered = unableWhileTapped))
        damage(services.combatManager.applyCombatDamage(declared).state, first) shouldBe 3
        val tapped = services.combatManager.applyCombatDamage(tap(declared, defender).state)
        damage(tapped.state, first) shouldBe 0
        damage(tapped.state, defender) shouldBe 2
    }

    test("a creature that cannot block alone needs another declared blocker to retaliate") {
        val declared = declare(state(attacker = trample, encountered = needsCoBlocker, withSupport = true))
        OtariaEncounterCombat.canRetaliate(declared, declared.projectedState, defender, PredicateEvaluator(registry), registry) shouldBe false
        val blocked = services.combatManager.declareBlockers(declared, actor, mapOf(support to listOf(first)))
        blocked.error shouldBe null
        OtariaEncounterCombat.canRetaliate(blocked.state, blocked.state.projectedState, defender, PredicateEvaluator(registry), registry) shouldBe true
    }

    test("a face-down encounter does not retain its printed can't-block ability") {
        val initial = state(encountered = unableToBlock)
            .updateEntity(defender) {
                it.with(FaceDownComponent).with(FaceDownModeComponent(FaceDownMode.MORPH))
                    .without<ContinuousEffectSourceComponent>()
            }
        val result = services.combatManager.applyCombatDamage(declare(initial))
        damage(result.state, first) shouldBe 2
    }

    test("attacker evasion does not prevent an otherwise eligible encounter from retaliating") {
        val flying = creature("Flying attacker", 2, 6, Keyword.FLYING)
        registry.register(flying)
        val result = services.combatManager.applyCombatDamage(declare(state(attacker = flying)))
        damage(result.state, first) shouldBe 3
    }

    test("another player joins an encounter only after a valid attack declaration") {
        val initial = state(secondAttacker = ground)
        val mode = requireNotNull(initial.otaria)
        val otherState = initial
            .updateEntity(first) { it.with(ControllerComponent(other)) }
            .updateEntity(second) { it.with(ControllerComponent(other)) }
            .copy(
                activePlayerId = other,
                priorityPlayerId = other,
                otaria = mode.copy(
                    players = mode.players + (other to mode.players.getValue(other).copy(position = mode.players.getValue(explorer).position)),
                    turn = OtariaTurn(other, 1, OtariaTurnAction.COMBAT),
                ),
            )
        val mixed = processor.process(otherState, DeclareAttackers(other, mapOf(first to defender, second to manager))).result
        mixed.error shouldNotBe null
        mixed.state.otaria?.encounters?.get(actor)?.participants shouldBe setOf(explorer)

        val result = processor.process(otherState, DeclareAttackers(other, mapOf(first to defender))).result
        result.error shouldBe null
        result.state.otaria?.encounters?.get(actor)?.participants shouldBe setOf(explorer, other)
        result.events.filterIsInstance<OtariaEncounterJoinedEvent>() shouldBe listOf(OtariaEncounterJoinedEvent(actor, other))
        (manager in CombatDefenders.attackTargets(result.state, result.state.projectedState, other)) shouldBe false
        (manager in CombatDefenders.enteringAttackTargets(result.state, other)) shouldBe false
        json.decodeFromString<ExecutionResult>(json.encodeToString(result)) shouldBe result
    }

    test("encounter targets respect range, control changes, and absence of the variant") {
        val initial = state()
        val mode = requireNotNull(initial.otaria)
        val distant = initial.copy(otaria = mode.copy(encounters = mode.encounters + (
            actor to mode.encounters.getValue(actor).copy(position = mode.players.getValue(other).position)
        )))
        (defender in CombatDefenders.attackTargets(distant, distant.projectedState, explorer)) shouldBe false
        processor.process(distant, DeclareAttackers(explorer, mapOf(first to defender))).result.error shouldNotBe null

        val stolen = initial.updateEntity(defender) { it.with(ControllerComponent(manager)) }
        (defender in CombatDefenders.attackTargets(stolen, stolen.projectedState, explorer)) shouldBe false
        val ordinary = initial.copy(otaria = null)
        (defender in CombatDefenders.attackTargets(ordinary, ordinary.projectedState, explorer)) shouldBe false
    }

    test("combat projections identify encounter creatures while masking face-down names") {
        val initial = state(attacker = trample, withSupport = true)
            .updateEntity(defender) { it.with(FaceDownComponent).with(FaceDownModeComponent(FaceDownMode.MORPH)) }
        val declared = declare(initial)
        val projector = ClientStateTransformer(registry, predicateEvaluator = PredicateEvaluator(registry))
        val view = projector.transform(declared, explorer)
        view.otaria?.encounters?.single()?.actorId shouldBe actor
        view.otaria?.encounters?.single()?.managerId shouldBe manager
        view.combat?.attackers?.single()?.attackingTarget shouldBe ClientCombatTarget.Creature(defender)

        val blocked = services.combatManager.declareBlockers(declared, actor, mapOf(support to listOf(first)))
        blocked.error shouldBe null
        val pending = services.combatManager.applyCombatDamage(blocked.state)
        val question = pending.state.pendingDecision as CombatResolutionDecision
        val visibility = Visibility(registry, conditionEvaluator = PredicateEvaluator(registry).conditions)
        val hidden = DecisionMasker(visibility).maskFor(question, pending.state, explorer) as CombatResolutionDecision
        hidden.defenders.single { it.id == defender }.name shouldBe FACE_DOWN_DISPLAY_NAME
        val managerView = DecisionMasker(visibility).maskFor(question, pending.state, manager) as CombatResolutionDecision
        managerView.defenders.single { it.id == defender }.name shouldBe plainDefender.name
    }
})
