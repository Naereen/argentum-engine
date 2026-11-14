package com.wingedsheep.engine.otaria

import com.wingedsheep.engine.core.ActionProcessor
import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.CardEntityFactory
import com.wingedsheep.engine.core.ChooseColorDecision
import com.wingedsheep.engine.core.ChooseNumberDecision
import com.wingedsheep.engine.core.CleanupPhaseManager
import com.wingedsheep.engine.core.ColorChosenResponse
import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.EngineServices
import com.wingedsheep.engine.core.ExecutionResult
import com.wingedsheep.engine.core.GameEndReason
import com.wingedsheep.engine.core.ManaAddedEvent
import com.wingedsheep.engine.core.ManaPoolChangedEvent
import com.wingedsheep.engine.core.ManaSourcesSelectedResponse
import com.wingedsheep.engine.core.NumberChosenResponse
import com.wingedsheep.engine.core.OtariaEncounterManaAddedEvent
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.core.TurnFaceUp
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.engine.handlers.DecisionHandler
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.effects.FaceDownTurnUp
import com.wingedsheep.engine.handlers.effects.ZoneTransitionService
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.mechanics.mana.ManaPool
import com.wingedsheep.engine.mechanics.mana.ManaPaymentWindow
import com.wingedsheep.engine.mechanics.mana.ManaUnit
import com.wingedsheep.engine.mechanics.mana.SpellPaymentContext
import com.wingedsheep.engine.mechanics.mana.buildAbilityPaymentContext
import com.wingedsheep.engine.mechanics.sba.player.PlayerLeavesGameProcessor
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.LifeTotalComponent
import com.wingedsheep.engine.state.components.identity.FaceDownComponent
import com.wingedsheep.engine.state.components.identity.FaceDownModeComponent
import com.wingedsheep.engine.state.components.identity.PlayerComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.battlefield.TappedComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.state.components.player.ManaSourceTag
import com.wingedsheep.engine.state.components.player.RestrictedManaEntry
import com.wingedsheep.engine.view.LegalActionEnricher
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.ConvertEmptyingMana
import com.wingedsheep.sdk.scripting.AdditionalManaOnSourceTap
import com.wingedsheep.sdk.scripting.AdditionalManaOnTap
import com.wingedsheep.sdk.scripting.DampLandManaProduction
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.KeywordAbility
import com.wingedsheep.sdk.scripting.TimingRule
import com.wingedsheep.sdk.scripting.effects.FaceDownMode
import com.wingedsheep.sdk.scripting.effects.DealDamageEffect
import com.wingedsheep.sdk.scripting.effects.ManaRestriction
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import com.wingedsheep.sdk.scripting.values.DynamicAmount
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class OtariaEncounterManaTest : FunSpec({
    val players = (1..3).map { EntityId("player-$it") }
    val explorer = players.first()
    val actor = EntityId("encounter")
    val creatureId = EntityId("creature")
    val forest = CardDefinition.basicLand("Forest", Subtype.FOREST)
    val creature = CardDefinition.creature(
        name = "Encounter Creature",
        manaCost = ManaCost.parse("{G}"),
        subtypes = setOf(Subtype.ELF),
        power = 2,
        toughness = 2,
    )
    val pump = card("Encounter Mana Bear") {
        typeLine = "Creature — Bear"
        power = 2
        toughness = 2
        activatedAbility {
            cost = Costs.Mana("{G}")
            effect = Effects.ModifyStats(1, 0, EffectTarget.Self)
        }
    }
    val morph = CardDefinition(
        name = "Encounter Mana Morph",
        manaCost = ManaCost.parse("{2}{G}"),
        typeLine = com.wingedsheep.sdk.core.TypeLine.creature(),
        creatureStats = com.wingedsheep.sdk.model.CreatureStats(2, 2),
        keywordAbilities = listOf(KeywordAbility.Morph(ManaCost.parse("{G}"))),
    )
    val druid = card("Encounter Mana Druid") {
        typeLine = "Creature — Elf Druid"
        power = 2
        toughness = 2
        activatedAbility {
            cost = Costs.Tap
            effect = Effects.AddMana(Color.GREEN, 2)
            manaAbility = true
            timing = TimingRule.ManaAbility
        }
        activatedAbility {
            cost = Costs.Mana("{G}")
            effect = Effects.ModifyStats(1, 0, EffectTarget.Self)
        }
    }
    val prism = card("Encounter Mana Prism") {
        typeLine = "Creature — Elf Druid"
        power = 2
        toughness = 2
        activatedAbility {
            cost = Costs.Tap
            effect = Effects.AddManaOfChoice(amount = 2)
            manaAbility = true
            timing = TimingRule.ManaAbility
        }
    }
    val splitDruid = card("Encounter Split Druid") {
        typeLine = "Snow Creature — Elf Druid"
        power = 2
        toughness = 2
        activatedAbility {
            cost = Costs.Tap
            effect = Effects.AddDynamicMana(DynamicAmount.Fixed(2), setOf(Color.GREEN, Color.RED))
            manaAbility = true
        }
    }
    val pipDruid = card("Encounter Pip Druid") {
        typeLine = "Snow Creature — Elf Druid"
        power = 2
        toughness = 2
        activatedAbility {
            cost = Costs.Tap
            effect = Effects.AddDynamicMana(DynamicAmount.Fixed(2), Color.entries.toSet())
            manaAbility = true
        }
    }
    val singleDruid = card("Encounter Single Druid") {
        typeLine = "Snow Creature — Elf Druid"
        power = 2
        toughness = 2
        activatedAbility {
            cost = Costs.Tap
            effect = Effects.AddDynamicMana(DynamicAmount.Fixed(2), setOf(Color.GREEN))
            manaAbility = true
        }
    }
    val colorsDruid = card("Encounter Colors Druid") {
        manaCost = "{G}{U}"
        typeLine = "Snow Creature — Elf Druid"
        power = 2
        toughness = 2
        activatedAbility {
            cost = Costs.Tap
            effect = Effects.AddOneManaOfEachColorAmong(GameObjectFilter.Any)
            manaAbility = true
        }
    }
    val twinLand = card("Encounter Twin Land") {
        typeLine = "Snow Land"
        activatedAbility {
            cost = Costs.Tap
            effect = Effects.AddMana(Color.GREEN, 2)
            manaAbility = true
        }
    }
    val dampener = card("Encounter Dampener") {
        typeLine = "Artifact"
        staticAbility { ability = DampLandManaProduction }
    }
    val fixedAura = card("Encounter Fixed Aura") {
        typeLine = "Enchantment — Aura"
        staticAbility { ability = AdditionalManaOnTap(color = Color.RED, amount = DynamicAmount.Fixed(1)) }
    }
    val choiceAura = card("Encounter Choice Aura") {
        typeLine = "Enchantment — Aura"
        staticAbility { ability = AdditionalManaOnTap(color = null, amount = DynamicAmount.Fixed(1), anyColor = true) }
    }
    val mirrorBonus = card("Encounter Mirror Bonus") {
        typeLine = "Enchantment"
        staticAbility { ability = AdditionalManaOnSourceTap(GameObjectFilter.Land, color = null) }
    }
    val damageBonus = card("Encounter Damage Bonus") {
        typeLine = "Enchantment"
        staticAbility {
            ability = AdditionalManaOnSourceTap(
                GameObjectFilter.Land, color = null,
                rider = DealDamageEffect(1, EffectTarget.Controller, damageSource = EffectTarget.Self),
            )
        }
    }
    val registry = CardRegistry().also {
        listOf(
            forest, creature, pump, morph, druid, prism, splitDruid, pipDruid, singleDruid,
            colorsDruid, twinLand, dampener, fixedAura, choiceAura, mirrorBonus, damageBonus,
        ).forEach(it::register)
    }
    val services = EngineServices(registry)
    val processor = ActionProcessor(services)
    val zones = ZoneTransitionService(registry, services.predicateEvaluator)
    val cleanup = CleanupPhaseManager(registry, DecisionHandler(), services.conditionEvaluator)
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
            turn = OtariaTurn(explorer, 1, OtariaTurnAction.EXPLORE),
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
            step = Step.END,
            phase = Step.END.phase,
            otaria = mode,
        )
        current = current.withEntity(creatureId, CardEntityFactory.create(creature, actor))
            .addToZone(ZoneKey(actor, Zone.BATTLEFIELD), creatureId)
        val encounter = OtariaEncounter(
            explorerId = explorer,
            managerId = players[1],
            position = mode.players.getValue(explorer).position,
            source = OtariaExplorationSource.Terrain(Color.GREEN),
            primaryId = creatureId,
            members = setOf(requireNotNull(current.objectRef(creatureId))),
        )
        return current.copy(otaria = mode.copy(encounters = mapOf(actor to encounter)))
    }

    fun addLands(state: GameState, playerId: EntityId, count: Int): GameState {
        var current = state
        repeat(count) { index ->
            val id = EntityId("$playerId-land-$index")
            current = current.withEntity(id, CardEntityFactory.create(forest, playerId))
                .addToZone(ZoneKey(playerId, Zone.BATTLEFIELD), id)
        }
        return current
    }

    fun answer(state: GameState, color: Color) =
        processor.process(state, SubmitDecision(
            actor,
            ColorChosenResponse(requireNotNull(state.pendingDecision).id, color),
        )).result
    fun ref(state: GameState, id: EntityId = creatureId) = requireNotNull(state.objectRef(id))

    fun withSecondCreature(state: GameState, definition: CardDefinition = creature): Pair<GameState, EntityId> {
        val second = EntityId("creature-two")
        val withCard = state.withEntity(second, CardEntityFactory.create(definition, actor))
            .addToZone(ZoneKey(actor, Zone.BATTLEFIELD), second)
        val mode = requireNotNull(withCard.otaria)
        val encounter = mode.encounters.getValue(actor)
        return withCard.copy(otaria = mode.copy(encounters = mode.encounters + (
            actor to encounter.copy(members = encounter.members + ref(withCard, second))
        ))) to second
    }

    fun withAssignedLand(): Pair<GameState, EntityId> {
        val (withLand, land) = withSecondCreature(state(), twinLand)
        val mode = requireNotNull(withLand.otaria)
        val encounter = mode.encounters.getValue(actor)
        return withLand.copy(
            activePlayerId = actor, priorityPlayerId = actor, step = Step.PRECOMBAT_MAIN,
            phase = Step.PRECOMBAT_MAIN.phase,
            otaria = mode.copy(encounters = mode.encounters + (
                actor to encounter.copy(manaSources = listOf(OtariaManaSourceAssignment(ref(withLand, land), ref(withLand))))
            )),
        ) to land
    }

    fun addPermanent(state: GameState, id: EntityId, definition: CardDefinition, attachedTo: EntityId? = null): GameState {
        val card = CardEntityFactory.create(definition, explorer)
        return state.withEntity(id, if (attachedTo == null) card else card.with(AttachedToComponent(attachedTo)))
            .addToZone(ZoneKey(explorer, Zone.BATTLEFIELD), id)
    }

    test("the manager chooses each mana color at the encounter's turn start") {
        val initial = addLands(addLands(state(), explorer, 2), players[2], 1)
        val started = services.turnManager.passTurn(initial)
        started.error shouldBe null
        val firstDecision = started.state.pendingDecision as ChooseColorDecision
        firstDecision.playerId shouldBe actor
        started.state.actorFor(actor) shouldBe players[1]
        val restored = json.decodeFromString<GameState>(json.encodeToString(started.state))
        val wrongSeat = processor.process(
            restored, SubmitDecision(players[1], ColorChosenResponse(firstDecision.id, Color.GREEN)),
        ).result
        wrongSeat.error shouldNotBe null
        wrongSeat.state shouldBe restored

        val first = answer(restored, Color.GREEN)
        first.error shouldBe null
        first.state.pendingDecision shouldNotBe null
        first.events.filterIsInstance<OtariaEncounterManaAddedEvent>() shouldBe
            listOf(OtariaEncounterManaAddedEvent(actor, ref(first.state), Color.GREEN))
        first.events.filterIsInstance<ManaAddedEvent>() shouldBe emptyList()
        val second = answer(first.state, Color.BLUE)
        second.error shouldBe null
        second.state.pendingDecision shouldBe null
        second.state.step shouldBe Step.UPKEEP
        val pool = requireNotNull(second.state.getEntity(actor)?.get<ManaPoolComponent>())
        pool.restrictedMana.map { it.color } shouldBe listOf(Color.GREEN, Color.BLUE)
        pool.restrictedMana.all { it.assignedPermanent == ref(second.state) && it.remainsUntilSpent } shouldBe true
        OtariaEncounterMana.needed(second.state, actor, ref(second.state)) shouldBe 0
    }

    test("only encounter rule mana survives boundaries and counts against its cap") {
        val initial = addLands(state(), explorer, 2)
        val creature = ref(initial)
        val withGreen = OtariaEncounterMana.add(initial, actor, creature, Color.GREEN).state
        val withBoth = OtariaEncounterMana.add(withGreen, actor, creature, Color.BLUE).state
        val mixed = withBoth.updateEntity(actor) {
            it.with(requireNotNull(it.get<ManaPoolComponent>()).add(Color.RED))
        }.updateEntity(explorer) { it.with(ManaPoolComponent(red = 1)) }
        val emptied = cleanup.emptyManaPools(mixed)
        val pool = requireNotNull(emptied.getEntity(actor)?.get<ManaPoolComponent>())
        pool.restrictedMana.map { it.color } shouldBe listOf(Color.GREEN, Color.BLUE)
        pool.red shouldBe 0
        emptied.getEntity(explorer)?.get<ManaPoolComponent>()?.total shouldBe 0
        OtariaEncounterMana.needed(emptied, actor, creature) shouldBe 0

        val converted = requireNotNull(mixed.getEntity(actor)?.get<ManaPoolComponent>())
            .emptyAtBoundary(ConvertEmptyingMana(Color.BLACK), emptySet())
        converted.black shouldBe 1
        converted.restrictedMana.map { it.color } shouldBe listOf(Color.GREEN, Color.BLUE)
        converted.red shouldBe 0

        val spent = requireNotNull(ManaPool(restrictedMana = pool.restrictedMana).pay(
            ManaCost.parse("{G}"), SpellPaymentContext(assignedPermanent = creature),
        ))
        val afterSpend = emptied.updateEntity(actor) { it.with(pool.copy(restrictedMana = spent.restrictedMana)) }
        OtariaEncounterMana.needed(afterSpend, actor, creature) shouldBe 1
        cleanup.emptyManaPools(afterSpend).getEntity(actor)?.get<ManaPoolComponent>()
            ?.restrictedMana?.map { it.color } shouldBe listOf(Color.BLUE)
        pool.empty().total shouldBe 0
    }

    test("each encountered creature gets an independent reserve and payment context") {
        val (withBoth, other) = withSecondCreature(addLands(state(), explorer, 2))
        val started = services.turnManager.passTurn(withBoth)
        var current = started.state
        val expected = listOf(creatureId, creatureId, other, other)
        for ((index, id) in expected.withIndex()) {
            (current.pendingDecision as ChooseColorDecision).context.sourceId shouldBe id
            val result = answer(current, if (index < 2) Color.GREEN else Color.BLUE)
            result.error shouldBe null
            current = result.state
        }
        current.pendingDecision shouldBe null
        val pool = requireNotNull(current.getEntity(actor)?.get<ManaPoolComponent>())
        val firstRef = ref(current)
        val secondRef = ref(current, other)
        pool.restrictedMana.count { it.assignedPermanent == firstRef } shouldBe 2
        pool.restrictedMana.count { it.assignedPermanent == secondRef } shouldBe 2
        val floating = ManaPool(restrictedMana = pool.restrictedMana)
        val first = SpellPaymentContext(assignedPermanent = firstRef)
        val second = SpellPaymentContext(assignedPermanent = secondRef)
        floating.canPay(ManaCost.parse("{G}"), null) shouldBe false
        floating.canPay(ManaCost.parse("{G}"), second) shouldBe false
        floating.canPay(ManaCost.parse("{U}"), first) shouldBe false
        val paid = requireNotNull(floating.pay(ManaCost.parse("{G}"), first))
        paid.restrictedMana.count { it.assignedPermanent == firstRef } shouldBe 1
        paid.restrictedMana.count { it.assignedPermanent == secondRef } shouldBe 2

        val exiled = zones.moveToZone(current, creatureId, Zone.EXILE)
        exiled.events.filterIsInstance<ManaPoolChangedEvent>().map { it.playerId } shouldBe listOf(actor)
        val afterExit = requireNotNull(exiled.state.getEntity(actor)?.get<ManaPoolComponent>())
        afterExit.restrictedMana.count { it.assignedPermanent == firstRef } shouldBe 0
        afterExit.restrictedMana.count { it.assignedPermanent == secondRef } shouldBe 2
        val moved = zones.moveToZone(exiled.state, creatureId, Zone.BATTLEFIELD).state
        val newRef = ref(moved)
        (newRef != firstRef) shouldBe true
        floating.canPay(ManaCost.parse("{G}"), SpellPaymentContext(assignedPermanent = newRef)) shouldBe false
        OtariaEncounterMana.memberRef(moved, creatureId) shouldBe null
    }

    test("noncreature reinforcements do not receive encounter mana") {
        val (current, landId) = withSecondCreature(addLands(state(), explorer, 1), forest)
        val land = ref(current, landId)
        OtariaEncounterMana.needed(current, actor, land) shouldBe 0
        val rejected = OtariaEncounterMana.add(current, actor, land, Color.GREEN)
        rejected.error shouldNotBe null
        rejected.state shouldBe current
        val decision = requireNotNull(OtariaEncounterMana.chooseNext(current, actor))
            .state.pendingDecision as ChooseColorDecision
        decision.context.sourceId shouldBe creatureId
    }

    test("a legacy shared encounter pool is rejected after serialization") {
        val initial = state()
        val oldPool = ManaPoolComponent(
            green = 1,
            manaUnits = listOf(ManaUnit(Color.GREEN, ManaSourceTag(creatureId), survivesStepAndTurnEnds = true)),
        )
        val legacy = initial.updateEntity(actor) { it.with(oldPool) }
        val restored = json.decodeFromString<GameState>(json.encodeToString(legacy))
        val result = processor.process(restored, PassPriority(explorer)).result
        requireNotNull(result.error) shouldContain "unassigned encounter mana"
        result.state shouldBe restored
        OtariaEncounterMana.hasUnassignedLegacyMana(restored.copy(otaria = null)) shouldBe false
    }

    test("activated abilities cannot spend another encountered creature's reserve") {
        val base = addLands(state(), explorer, 1)
            .withEntity(creatureId, CardEntityFactory.create(pump, actor))
        val (withBoth, other) = withSecondCreature(base, pump)
        val current = OtariaEncounterMana.add(withBoth, actor, ref(withBoth), Color.GREEN).state.copy(
            activePlayerId = actor, priorityPlayerId = actor, step = Step.PRECOMBAT_MAIN,
            phase = Step.PRECOMBAT_MAIN.phase,
        )
        val ability = pump.activatedAbilities.single().id
        val denied = processor.process(current, ActivateAbility(actor, other, ability, paymentStrategy = PaymentStrategy.FromPool)).result
        denied.error shouldNotBe null
        denied.state shouldBe current
        val paid = processor.process(current, ActivateAbility(actor, creatureId, ability, paymentStrategy = PaymentStrategy.FromPool)).result
        paid.error shouldBe null
        paid.state.getEntity(actor)?.get<ManaPoolComponent>()?.restrictedMana shouldBe emptyList()
    }

    test("automatic and explicit payments cannot tap another creature's mana source") {
        val base = state().withEntity(creatureId, CardEntityFactory.create(druid, actor))
        val (withBoth, other) = withSecondCreature(base, pump)
        val current = withBoth.copy(
            activePlayerId = actor, priorityPlayerId = actor, step = Step.PRECOMBAT_MAIN,
            phase = Step.PRECOMBAT_MAIN.phase,
        )
        val otherContext = buildAbilityPaymentContext(
            requireNotNull(current.getEntity(other)?.get<CardComponent>()),
            current.projectedState, other, pump.activatedAbilities.single(),
        )
        val cached = services.manaSolver.findAvailableManaSources(current, actor)
        cached.any { it.entityId == creatureId } shouldBe true
        services.manaSolver.canPay(
            current, actor, ManaCost.parse("{G}"), spellContext = otherContext, precomputedSources = cached,
        ) shouldBe false
        val ability = pump.activatedAbilities.single().id
        val denied = processor.process(current, ActivateAbility(actor, other, ability)).result
        denied.error shouldNotBe null
        denied.state shouldBe current
        val explicit = processor.process(
            current, ActivateAbility(actor, other, ability, paymentStrategy = PaymentStrategy.Explicit(listOf(creatureId))),
        ).result
        explicit.error shouldNotBe null
        explicit.state shouldBe current

        val paid = processor.process(current, ActivateAbility(actor, creatureId, druid.activatedAbilities[1].id)).result
        paid.error shouldBe null
        paid.state.getEntity(creatureId)?.has<TappedComponent>() shouldBe true
        val remainder = requireNotNull(paid.state.getEntity(actor)?.get<ManaPoolComponent>()).restrictedMana.single()
        remainder.color shouldBe Color.GREEN
        remainder.assignedPermanent shouldBe ref(paid.state)
        remainder.remainsUntilSpent shouldBe false
        remainder.source?.sourceId shouldBe creatureId
        ManaPool(restrictedMana = listOf(remainder)).canPay(ManaCost.parse("{G}"), otherContext) shouldBe false
        cleanup.emptyManaPools(paid.state).getEntity(actor)?.get<ManaPoolComponent>()?.total shouldBe 0
    }

    test("manual mana belongs only to its producer and keeps its provenance") {
        val base = state().withEntity(creatureId, CardEntityFactory.create(druid, actor))
        val (withBoth, other) = withSecondCreature(base, pump)
        val current = withBoth.copy(
            activePlayerId = actor, priorityPlayerId = actor, step = Step.PRECOMBAT_MAIN,
            phase = Step.PRECOMBAT_MAIN.phase,
        )
        val produced = processor.process(current, ActivateAbility(actor, creatureId, druid.activatedAbilities[0].id)).result
        produced.error shouldBe null
        val pool = requireNotNull(produced.state.getEntity(actor)?.get<ManaPoolComponent>())
        pool.green shouldBe 0
        pool.restrictedMana.size shouldBe 2
        pool.restrictedMana.all {
            it.assignedPermanent == ref(produced.state) && !it.remainsUntilSpent && it.source?.sourceId == creatureId
        } shouldBe true
        val ready = produced.state.copy(priorityPlayerId = actor)
        val denied = processor.process(
            ready, ActivateAbility(actor, other, pump.activatedAbilities.single().id, paymentStrategy = PaymentStrategy.FromPool),
        ).result
        denied.error shouldNotBe null
        denied.state shouldBe ready
        val paid = processor.process(
            ready, ActivateAbility(actor, creatureId, druid.activatedAbilities[1].id, paymentStrategy = PaymentStrategy.FromPool),
        ).result
        paid.error shouldBe null
        paid.state.getEntity(actor)?.get<ManaPoolComponent>()?.restrictedMana?.size shouldBe 1
    }

    test("a resumed mana color choice retains the creature assignment") {
        val current = state().withEntity(creatureId, CardEntityFactory.create(prism, actor)).copy(
            activePlayerId = actor, priorityPlayerId = actor, step = Step.PRECOMBAT_MAIN,
            phase = Step.PRECOMBAT_MAIN.phase,
        )
        val pending = processor.process(current, ActivateAbility(actor, creatureId, prism.activatedAbilities.single().id)).result
        pending.error shouldBe null
        (pending.state.pendingDecision as ChooseColorDecision).playerId shouldBe actor
        val restored = json.decodeFromString<GameState>(json.encodeToString(pending.state))
        val chosen = answer(restored, Color.BLUE)
        chosen.error shouldBe null
        val pool = requireNotNull(chosen.state.getEntity(actor)?.get<ManaPoolComponent>())
        pool.blue shouldBe 0
        pool.restrictedMana.size shouldBe 2
        pool.restrictedMana.all {
            it.color == Color.BLUE && it.assignedPermanent == ref(chosen.state) && it.source?.sourceId == creatureId
        } shouldBe true
    }

    test("a noncreature source needs an explicit current-object assignment") {
        val base = state().withEntity(creatureId, CardEntityFactory.create(pump, actor))
        val (withLand, land) = withSecondCreature(base, forest)
        val mode = requireNotNull(withLand.otaria)
        val encounter = mode.encounters.getValue(actor)
        val current = withLand.copy(
            activePlayerId = actor, priorityPlayerId = actor, step = Step.PRECOMBAT_MAIN,
            phase = Step.PRECOMBAT_MAIN.phase,
        )
        val ability = pump.activatedAbilities.single().id
        processor.process(current, ActivateAbility(actor, creatureId, ability)).result.error shouldNotBe null

        val assigned = current.copy(otaria = mode.copy(encounters = mode.encounters + (
            actor to encounter.copy(manaSources = listOf(OtariaManaSourceAssignment(ref(current, land), ref(current))))
        )))
        val restored = json.decodeFromString<GameState>(json.encodeToString(assigned))
        val paid = processor.process(restored, ActivateAbility(actor, creatureId, ability)).result
        paid.error shouldBe null
        paid.state.getEntity(land)?.has<TappedComponent>() shouldBe true
        val left = zones.moveToZone(restored, land, Zone.EXILE).state
        val returned = zones.moveToZone(left, land, Zone.BATTLEFIELD).state
        OtariaEncounterMana.sourceOwner(returned, actor, land) shouldBe null
    }

    test("dynamic mana choices retain their creature and snow provenance across saves") {
        fun ready(definition: CardDefinition): GameState =
            state().withEntity(creatureId, CardEntityFactory.create(definition, actor)).copy(
                activePlayerId = actor, priorityPlayerId = actor, step = Step.PRECOMBAT_MAIN,
                phase = Step.PRECOMBAT_MAIN.phase,
            )
        fun assertUnits(state: GameState, colors: Set<Color>) {
            val units = requireNotNull(state.getEntity(actor)?.get<ManaPoolComponent>()).restrictedMana
            units.mapNotNull { it.color }.toSet() shouldBe colors
            units.size shouldBe 2
            units.all {
                it.assignedPermanent == ref(state) && it.source?.sourceId == creatureId &&
                    it.source?.isSnow == true && !it.remainsUntilSpent
            } shouldBe true
        }

        val split = processor.process(
            ready(splitDruid), ActivateAbility(actor, creatureId, splitDruid.activatedAbilities.single().id),
        ).result
        split.error shouldBe null
        val number = split.state.pendingDecision as ChooseNumberDecision
        val restored = json.decodeFromString<GameState>(json.encodeToString(split.state))
        val splitResult = processor.process(restored, SubmitDecision(actor, NumberChosenResponse(number.id, 1))).result
        splitResult.error shouldBe null
        assertUnits(splitResult.state, setOf(Color.GREEN, Color.RED))

        val pips = processor.process(
            ready(pipDruid), ActivateAbility(actor, creatureId, pipDruid.activatedAbilities.single().id),
        ).result
        pips.error shouldBe null
        val first = answer(json.decodeFromString<GameState>(json.encodeToString(pips.state)), Color.BLUE)
        first.error shouldBe null
        val second = answer(json.decodeFromString<GameState>(json.encodeToString(first.state)), Color.GREEN)
        second.error shouldBe null
        second.state.pendingDecision shouldBe null
        assertUnits(second.state, setOf(Color.BLUE, Color.GREEN))
        second.events.filterIsInstance<ManaAddedEvent>().single().total shouldBe 2

        val single = processor.process(
            ready(singleDruid), ActivateAbility(actor, creatureId, singleDruid.activatedAbilities.single().id),
        ).result
        single.error shouldBe null
        assertUnits(single.state, setOf(Color.GREEN))
        single.events.filterIsInstance<ManaAddedEvent>().single().green shouldBe 2
    }

    test("mana of each color among permanents is assigned and reported") {
        val current = state().withEntity(creatureId, CardEntityFactory.create(colorsDruid, actor)).copy(
            activePlayerId = actor, priorityPlayerId = actor, step = Step.PRECOMBAT_MAIN,
            phase = Step.PRECOMBAT_MAIN.phase,
        )
        val produced = processor.process(
            current, ActivateAbility(actor, creatureId, colorsDruid.activatedAbilities.single().id),
        ).result
        produced.error shouldBe null
        val pool = requireNotNull(produced.state.getEntity(actor)?.get<ManaPoolComponent>())
        pool.restrictedMana.mapNotNull { it.color }.toSet() shouldBe setOf(Color.BLUE, Color.GREEN)
        pool.restrictedMana.all {
            it.assignedPermanent == ref(produced.state) && it.source?.sourceId == creatureId && it.source?.isSnow == true
        } shouldBe true
        val event = produced.events.filterIsInstance<ManaAddedEvent>().single()
        event.blue shouldBe 1
        event.green shouldBe 1
    }

    test("dampened encounter-land mana remains assigned to its creature") {
        val (base, land) = withAssignedLand()
        val current = addPermanent(base, EntityId("dampener"), dampener)
        val produced = processor.process(current, ActivateAbility(actor, land, twinLand.activatedAbilities.single().id)).result
        produced.error shouldBe null
        val pool = requireNotNull(produced.state.getEntity(actor)?.get<ManaPoolComponent>())
        pool.colorless shouldBe 0
        val entry = pool.restrictedMana.single()
        entry.color shouldBe null
        entry.assignedPermanent shouldBe ref(produced.state)
        entry.source?.sourceId shouldBe land
        entry.source?.isSnow shouldBe true
        produced.events.filterIsInstance<ManaAddedEvent>().single().colorless shouldBe 1
    }

    test("dampening previews and production follow the source's Otaria range and projected controller") {
        val (base, land) = withAssignedLand()
        val mode = requireNotNull(base.otaria)
        val positioned = base.copy(otaria = mode.copy(
            players = mode.players + (
                explorer to mode.players.getValue(explorer).copy(position = OtariaPosition(6, 5))
            ) + (
                players[2] to mode.players.getValue(players[2]).copy(position = OtariaPosition(10, 11))
            ),
            encounters = mode.encounters + (
                actor to mode.encounters.getValue(actor).copy(position = OtariaPosition(6, 5))
            ),
        ))
        val sphere = EntityId("far-dampener")
        val distant = positioned.withEntity(sphere, CardEntityFactory.create(dampener, players[2]))
            .addToZone(ZoneKey(players[2], Zone.BATTLEFIELD), sphere)

        fun preview(state: GameState) = services.manaSolver.findAvailableManaSources(
            state, actor, buildAbilityPaymentContext(
                requireNotNull(state.getEntity(creatureId)?.get<CardComponent>()),
                state.projectedState, creatureId, null,
            ),
        ).single { it.entityId == land }
        fun activate(state: GameState) =
            processor.process(state, ActivateAbility(actor, land, twinLand.activatedAbilities.single().id)).result

        preview(distant).manaAmount shouldBe 2
        preview(distant).producesColors shouldBe setOf(Color.GREEN)
        val normal = activate(distant)
        normal.error shouldBe null
        normal.events.filterIsInstance<ManaAddedEvent>().single().green shouldBe 2

        val controlledNearby = distant.updateEntity(sphere) { it.with(ControllerComponent(actor)) }
        preview(controlledNearby).manaAmount shouldBe 1
        preview(controlledNearby).producesColorless shouldBe true
        val dampened = activate(controlledNearby)
        dampened.error shouldBe null
        dampened.events.filterIsInstance<ManaAddedEvent>().single().colorless shouldBe 1

        val actorSphere = positioned.withEntity(sphere, CardEntityFactory.create(dampener, actor))
            .addToZone(ZoneKey(actor, Zone.BATTLEFIELD), sphere)
        preview(actorSphere).producesColorless shouldBe true
        activate(actorSphere).events.filterIsInstance<ManaAddedEvent>().single().colorless shouldBe 1

        val faceDown = actorSphere.updateEntity(sphere) { it.with(FaceDownComponent) }
        preview(faceDown).producesColors shouldBe setOf(Color.GREEN)
        activate(faceDown).events.filterIsInstance<ManaAddedEvent>().single().green shouldBe 2
    }

    test("fixed and mirror tap bonuses belong to the land's assigned creature") {
        val (base, land) = withAssignedLand()
        val auraId = EntityId("fixed-aura")
        val bonusId = EntityId("mirror-bonus")
        val current = addPermanent(addPermanent(base, auraId, fixedAura, land), bonusId, mirrorBonus)
        val produced = processor.process(current, ActivateAbility(actor, land, twinLand.activatedAbilities.single().id)).result
        produced.error shouldBe null
        val entries = requireNotNull(produced.state.getEntity(actor)?.get<ManaPoolComponent>()).restrictedMana
        entries.size shouldBe 4
        entries.count { it.color == Color.GREEN && it.source?.sourceId == land } shouldBe 2
        entries.count { it.color == Color.RED && it.source?.sourceId == auraId } shouldBe 1
        entries.count { it.color == Color.GREEN && it.source?.sourceId == bonusId } shouldBe 1
        entries.all { it.assignedPermanent == ref(produced.state) } shouldBe true
    }

    test("a serialized any-color tap bonus uses the tapped land's assignment") {
        val (base, land) = withAssignedLand()
        val auraId = EntityId("choice-aura")
        val current = addPermanent(base, auraId, choiceAura, land)
        val paused = processor.process(current, ActivateAbility(actor, land, twinLand.activatedAbilities.single().id)).result
        paused.error shouldBe null
        paused.state.pendingDecision shouldNotBe null
        val restored = json.decodeFromString<GameState>(json.encodeToString(paused.state))
        val chosen = answer(restored, Color.BLUE)
        chosen.error shouldBe null
        val entries = requireNotNull(chosen.state.getEntity(actor)?.get<ManaPoolComponent>()).restrictedMana
        val bonus = entries.single { it.source?.sourceId == auraId }
        bonus.color shouldBe Color.BLUE
        bonus.assignedPermanent shouldBe ref(chosen.state)
        entries.size shouldBe 3
    }

    test("an unsupported tap-bonus rider rejects the entire encounter activation") {
        val (base, land) = withAssignedLand()
        val current = addPermanent(base, EntityId("damage-bonus"), damageBonus)
        val rejected = processor.process(current, ActivateAbility(actor, land, twinLand.activatedAbilities.single().id)).result
        requireNotNull(rejected.error) shouldContain "tap-bonus riders"
        rejected.state shouldBe current
        rejected.events shouldBe emptyList()
    }

    test("expiring encounter mana is spent before persistent rule mana") {
        val initial = addLands(state(), explorer, 1)
        val current = OtariaEncounterMana.add(initial, actor, ref(initial), Color.GREEN).state
        val persistent = requireNotNull(current.getEntity(actor)?.get<ManaPoolComponent>()).restrictedMana.single()
        val expiringGreen = RestrictedManaEntry(Color.GREEN, ManaRestriction.AnySpend, assignedPermanent = ref(current))
        val context = SpellPaymentContext(assignedPermanent = ref(current))
        val sameColor = ManaPool(restrictedMana = listOf(persistent, expiringGreen))
        requireNotNull(sameColor.pay(ManaCost.parse("{G}"), context)).restrictedMana shouldBe listOf(persistent)

        val expiringRed = expiringGreen.copy(color = Color.RED)
        val generic = ManaPool(restrictedMana = listOf(persistent, expiringRed))
        requireNotNull(generic.pay(ManaCost.parse("{1}"), context)).restrictedMana shouldBe listOf(persistent)
        generic.payPartial(ManaCost.parse("{1}"), context).newPool.restrictedMana shouldBe listOf(persistent)

        val exact = ManaPool(
            restrictedMana = listOf(persistent, expiringGreen.copy(obligationIds = setOf("already-used"))),
            dischargedObligations = setOf("already-used"),
        )
        requireNotNull(exact.pay(ManaCost.parse("{G}"), context)).restrictedMana shouldBe listOf(persistent)
    }

    test("encounter payment menus cannot expose or select another creature's sources") {
        val base = state().withEntity(creatureId, CardEntityFactory.create(druid, actor))
        val (withBoth, other) = withSecondCreature(base, druid)
        val current = withBoth.copy(
            activePlayerId = actor, priorityPlayerId = actor, step = Step.PRECOMBAT_MAIN,
            phase = Step.PRECOMBAT_MAIN.phase,
        )
        val action = ActivateAbility(actor, creatureId, druid.activatedAbilities[1].id)
        val legal = LegalAction(action, "ActivateAbility", "Pump", autoTapPreview = listOf(creatureId))
        val info = LegalActionEnricher(services.manaSolver, registry).enrich(listOf(legal), current, actor).single()
        info.availableManaSources?.map { it.entityId } shouldBe listOf(creatureId)
        info.availableManaSources?.any { it.entityId == other } shouldBe false

        val decision = ManaPaymentWindow.buildDecision(
            current, actor, ManaCost.parse("{G}"), "payment", "Pay", DecisionContext(),
            canDecline = true, manaSolver = services.manaSolver,
        )
        decision.availableSources shouldBe emptyList()
        decision.autoPaySuggestion shouldBe emptyList()
        val response = ManaSourcesSelectedResponse("payment", selectedSources = listOf(other))
        val rejected = processor.process(current, SubmitDecision(actor, response)).result
        requireNotNull(rejected.error) shouldContain "payment windows are not supported"
        rejected.state shouldBe current
    }

    test("removing an encounter member with its departing owner removes its reserve") {
        val original = addLands(state(), explorer, 1)
        val controlled = original.withEntity(
            creatureId, CardEntityFactory.create(creature, players[2]).with(ControllerComponent(actor)),
        )
        val funded = OtariaEncounterMana.add(controlled, actor, ref(controlled), Color.GREEN).state
        val result = PlayerLeavesGameProcessor.process(
            zones, funded, players[2], GameEndReason.CONCESSION,
        ) { ExecutionResult.success(it) }
        result.error shouldBe null
        result.state.getEntity(creatureId) shouldBe null
        result.state.getEntity(actor)?.get<ManaPoolComponent>()?.restrictedMana shouldBe emptyList()
        result.events.filterIsInstance<ManaPoolChangedEvent>().map { it.playerId } shouldBe listOf(actor)
    }

    test("turning a morph face up spends only that creature's reserve") {
        fun faceDown(): ComponentContainer = CardEntityFactory.create(morph, actor)
            .with(FaceDownComponent)
            .with(FaceDownModeComponent(FaceDownMode.MORPH))
            .with(requireNotNull(FaceDownTurnUp.dataFor(morph, morph.name, FaceDownMode.MORPH)))
        val base = addLands(state(), explorer, 1).withEntity(creatureId, faceDown())
        val (withBoth, other) = withSecondCreature(base, morph)
        val facedownBoth = withBoth.withEntity(other, faceDown())
        val current = OtariaEncounterMana.add(facedownBoth, actor, ref(facedownBoth), Color.GREEN).state.copy(
            activePlayerId = actor, priorityPlayerId = actor, step = Step.PRECOMBAT_MAIN,
            phase = Step.PRECOMBAT_MAIN.phase,
        )
        val denied = processor.process(current, TurnFaceUp(actor, other, paymentStrategy = PaymentStrategy.FromPool)).result
        denied.error shouldNotBe null
        denied.state shouldBe current
        val paid = processor.process(current, TurnFaceUp(actor, creatureId, paymentStrategy = PaymentStrategy.FromPool)).result
        paid.error shouldBe null
        paid.state.getEntity(creatureId)?.has<FaceDownComponent>() shouldBe false
        paid.state.getEntity(actor)?.get<ManaPoolComponent>()?.restrictedMana shouldBe emptyList()
    }

    test("encounter actors cannot draw from their empty library") {
        val initial = state()
        val turnDraw = services.turnManager.drawCards(initial, actor, 1)
        turnDraw.error shouldBe null
        turnDraw.state shouldBe initial
        turnDraw.events shouldBe emptyList()
        val effectDraw = services.effectExecutorRegistry.execute(
            initial, Effects.DrawCards(1), EffectContext(sourceId = creatureId, controllerId = actor),
        )
        effectDraw.error shouldBe null
        effectDraw.state shouldBe initial
        effectDraw.events shouldBe emptyList()
    }
})
