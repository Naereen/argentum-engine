package com.wingedsheep.engine.otaria

import com.wingedsheep.engine.core.ActionProcessor
import com.wingedsheep.engine.core.CardEntityFactory
import com.wingedsheep.engine.core.CardsSelectedResponse
import com.wingedsheep.engine.core.ChooseColorDecision
import com.wingedsheep.engine.core.ColorChosenResponse
import com.wingedsheep.engine.core.EngineServices
import com.wingedsheep.engine.core.ExecutionResult
import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.core.OtariaEncounterManaAddedEvent
import com.wingedsheep.engine.core.OtariaEncounterStartedEvent
import com.wingedsheep.engine.core.OtariaEncounterSupportHandCreatedEvent
import com.wingedsheep.engine.core.OtariaReinforcementRolledEvent
import com.wingedsheep.engine.core.Outcome
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.core.ZoneChangeEvent
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.FACE_DOWN_DISPLAY_NAME
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.chosenColor
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.identity.FaceDownComponent
import com.wingedsheep.engine.state.components.identity.LifeTotalComponent
import com.wingedsheep.engine.state.components.identity.MorphDataComponent
import com.wingedsheep.engine.state.components.identity.PlayerComponent
import com.wingedsheep.engine.state.components.identity.TokenComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.view.ClientEvent
import com.wingedsheep.engine.view.ClientEventTransformer
import com.wingedsheep.engine.view.ClientStateTransformer
import com.wingedsheep.engine.view.DecisionMasker
import com.wingedsheep.engine.view.Visibility
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.CreatureStats
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.model.GameRng
import com.wingedsheep.sdk.scripting.ChoiceType
import com.wingedsheep.sdk.scripting.EntersAsCopy
import com.wingedsheep.sdk.scripting.EntersWithChoice
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.KeywordAbility
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class OtariaEncounterEntryTest : FunSpec({
    val players = (1..3).map { EntityId("player-$it") }
    val explorer = players[0]
    val manager = players[1]
    val forest = CardDefinition.basicLand("Forest", Subtype.FOREST)
    val reserveSpell = card("Encounter Reserve Spell") {
        typeLine = "Sorcery"
        spell { effect = Effects.GainLife(1) }
    }
    val watcher = card("Encounter Entry Watcher") {
        typeLine = "Creature — Human"
        power = 2
        toughness = 2
        triggeredAbility {
            trigger = Triggers.another(GameObjectFilter.Creature.youControl()).enters()
            effect = Effects.GainLife(1)
        }
    }
    val morph = CardDefinition(
        name = "Secret Encounter Morph",
        manaCost = ManaCost.parse("{3}{G}"),
        typeLine = TypeLine.creature(setOf(Subtype.ELF)),
        creatureStats = CreatureStats(4, 5),
        keywordAbilities = listOf(KeywordAbility.Morph(ManaCost.parse("{G}"))),
    )
    val legion = card("Encounter Legion") {
        typeLine = "Creature — Zombie"
        power = 2
        toughness = 2
    }
    val choice = card("Encounter Entry Chooser") {
        typeLine = "Creature — Sliver"
        power = 2
        toughness = 2
        replacementEffect(EntersWithChoice(ChoiceType.COLOR))
    }
    val unsupportedChoice = card("Encounter Opponent Chooser") {
        typeLine = "Creature — Human"
        power = 2
        toughness = 2
        replacementEffect(EntersWithChoice(ChoiceType.OPPONENT))
    }
    val clone = card("Encounter Entry Clone") {
        typeLine = "Creature — Shapeshifter"
        power = 2
        toughness = 2
        replacementEffect(EntersAsCopy())
    }
    val registry = CardRegistry().also {
        listOf(forest, reserveSpell, watcher, morph, legion, choice, unsupportedChoice, clone).forEach(it::register)
    }
    val services = EngineServices(registry)
    val processor = ActionProcessor(services)
    val entry = OtariaEncounterEntry(services.zones)
    val visibility = Visibility(registry, conditionEvaluator = services.predicateEvaluator.conditions)
    val transformer = ClientStateTransformer(registry, predicateEvaluator = services.predicateEvaluator)
    val json = Json { serializersModule = engineSerializersModule; allowStructuredMapKeys = true }
    val goblin = OtariaReinforcementContent.CreatureToken(setOf(Color.RED), setOf(Subtype.GOBLIN), 1, 1)

    fun initial(
        definition: CardDefinition,
        support: OtariaEncounterSupport = OtariaEncounterSupport(),
        source: OtariaExplorationSource = OtariaExplorationSource.Terrain(Color.RED),
        managerId: EntityId = manager,
    ): GameState {
        val locations = OtariaLocation.entries.take(players.size)
        val mode = OtariaState(
            players = players.zip(locations).associate { (id, location) ->
                id to OtariaPlayerState(location, location.position)
            },
            explorationsUsed = OtariaLocation.entries.associateWith { 0 },
            starPile = OtariaExplorationPile(emptyList()),
            colorPiles = Color.entries.associateWith { OtariaExplorationPile(emptyList()) },
            treasurePile = emptyList(),
            locationPile = emptyList(),
            pendingExploration = OtariaPendingExploration(
                explorer, managerId, source,
                OtariaPileCard(definition.name, if (source is OtariaExplorationSource.Star) OtariaCardKind.BOSS else OtariaCardKind.CREATURE),
            ),
            turn = OtariaTurn(explorer, 1, OtariaTurnAction.EXPLORE),
            encounterSupport = when (source) {
                is OtariaExplorationSource.Star -> OtariaEncounterSupportTable(stars = mapOf(definition.name to support))
                is OtariaExplorationSource.Terrain ->
                    OtariaEncounterSupportTable(colors = mapOf(source.color to mapOf(definition.name to support)))
            },
        )
        return GameState(
            entities = players.associateWith {
                ComponentContainer.of(PlayerComponent(it.value, 15), LifeTotalComponent(15), ManaPoolComponent())
            },
            turnOrder = players,
            activePlayerId = explorer,
            priorityPlayerId = explorer,
            turnNumber = 1,
            step = Step.OTARIA_EXPLORATION,
            phase = Step.OTARIA_EXPLORATION.phase,
            rng = GameRng.seeded(17),
            otaria = mode,
        )
    }

    fun addPermanent(state: GameState, id: EntityId, owner: EntityId, definition: CardDefinition): GameState =
        state.withEntity(id, CardEntityFactory.create(definition, owner))
            .addToZone(ZoneKey(owner, Zone.BATTLEFIELD), id)

    fun started(result: ExecutionResult) = result.events.filterIsInstance<OtariaEncounterStartedEvent>().single()

    test("rule reinforcements enter simultaneously and observe each other's entries") {
        val support = OtariaEncounterSupport(listOf(
            OtariaReinforcementGroup(goblin, OtariaReinforcementCount.Fixed(2)),
            OtariaReinforcementGroup(
                OtariaReinforcementContent.Card(reserveSpell.name, OtariaReinforcementDestination.HAND),
                OtariaReinforcementCount.Fixed(1),
            ),
        ))
        val before = initial(watcher, support)
        val result = entry.start(before)
        result.error shouldBe null
        result.outcome shouldBe Outcome.Done
        before.getBattlefield() shouldBe emptyList()
        val encounter = started(result)
        encounter.explorerId shouldBe explorer
        encounter.managerId shouldBe manager
        result.state.actorFor(encounter.actorId) shouldBe manager
        result.state.activePlayers shouldBe players
        result.state.otaria?.pendingExploration shouldBe null
        result.state.otaria?.encounters?.get(encounter.actorId)?.pileCard?.name shouldBe watcher.name

        val entries = result.events.filterIsInstance<ZoneChangeEvent>().filter { it.toZone == Zone.BATTLEFIELD }
        entries.size shouldBe 3
        entries.map { it.fromZone }.distinct() shouldBe listOf(null)
        entries.map { it.simultaneousEntryGroup }.distinct().size shouldBe 1
        entries.first().simultaneousEntryGroup shouldNotBe null
        entries.mapNotNull { it.newObject }.toSet() shouldBe encounter.members
        val tokens = encounter.members.filter { result.state.getEntity(it.entityId)?.has<TokenComponent>() == true }
        tokens.size shouldBe 2
        tokens.forEach {
            val card = requireNotNull(result.state.getEntity(it.entityId)?.get<CardComponent>())
            card.colors shouldBe setOf(Color.RED)
            card.typeLine.subtypes shouldBe setOf(Subtype.GOBLIN)
            result.state.projectedState.getPower(it.entityId) shouldBe 1
            result.state.projectedState.getToughness(it.entityId) shouldBe 1
        }
        services.triggerDetector.detectTriggers(result.state, result.events)
            .count { it.sourceId == encounter.primary.entityId } shouldBe 2
        val hand = result.events.filterIsInstance<OtariaEncounterSupportHandCreatedEvent>().single().cardIds
        result.state.getZone(encounter.actorId, Zone.HAND) shouldBe hand
        transformer.transform(result.state, manager).cards.getValue(hand.single()).name shouldBe reserveSpell.name
        transformer.transform(result.state, explorer).cards.containsKey(hand.single()) shouldBe false
    }

    test("a morph arrives face down and its rule-supplied copy has only public characteristics") {
        val support = OtariaEncounterSupport(listOf(OtariaReinforcementGroup(
            OtariaReinforcementContent.EncounteredCardCopyToken, OtariaReinforcementCount.Fixed(1),
        )))
        val result = entry.start(initial(morph, support))
        result.error shouldBe null
        val encounter = started(result)
        val primary = encounter.primary.entityId
        result.state.getEntity(primary)?.has<FaceDownComponent>() shouldBe true
        result.state.getEntity(primary)?.has<MorphDataComponent>() shouldBe true
        result.state.projectedState.getPower(primary) shouldBe 2
        result.state.projectedState.getToughness(primary) shouldBe 2
        val copy = encounter.members.single { it.entityId != primary }.entityId
        val copyEntity = requireNotNull(result.state.getEntity(copy))
        copyEntity.has<TokenComponent>() shouldBe true
        copyEntity.has<FaceDownComponent>() shouldBe false
        copyEntity.has<MorphDataComponent>() shouldBe false
        val copiedCard = requireNotNull(copyEntity.get<CardComponent>())
        copiedCard.name shouldBe ""
        copiedCard.cardDefinitionId shouldBe "face-down"
        copiedCard.colors shouldBe emptySet()
        result.state.projectedState.getPower(copy) shouldBe 2
        result.state.projectedState.getToughness(copy) shouldBe 2
        transformer.transform(result.state, manager).cards.getValue(primary).name shouldBe morph.name
        transformer.transform(result.state, explorer).cards.getValue(primary).name shouldBe FACE_DOWN_DISPLAY_NAME
        transformer.transform(result.state, manager, isSpectator = true)
            .cards.getValue(primary).name shouldBe FACE_DOWN_DISPLAY_NAME
        val visibleEntry = ClientEventTransformer.transform(result.events, explorer, result.state, visibility)
            .filterIsInstance<ClientEvent.PermanentEntered>().single { it.cardId == primary }
        visibleEntry.cardName shouldBe FACE_DOWN_DISPLAY_NAME
    }

    test("entry mana is chosen for every creature by the manager without advancing the explorer's turn") {
        val support = OtariaEncounterSupport(listOf(OtariaReinforcementGroup(goblin, OtariaReinforcementCount.Fixed(1))))
        val base = initial(morph, support)
        val before = addPermanent(addPermanent(base, EntityId("land-one"), explorer, forest), EntityId("land-two"), explorer, forest)
        val first = entry.start(before)
        (first.outcome is Outcome.Paused) shouldBe true
        val actorId = started(first).actorId
        val decision = first.state.pendingDecision as ChooseColorDecision
        decision.playerId shouldBe actorId
        first.state.actorFor(actorId) shouldBe manager
        decision.prompt shouldNotContain morph.name
        val masked = DecisionMasker(visibility).maskFor(decision, first.state, explorer)
        masked.context.sourceName shouldNotBe morph.name
        val restored = json.decodeFromString<ExecutionResult>(json.encodeToString(first))
        restored shouldBe first

        val wrongSeat = processor.process(
            restored.state, SubmitDecision(manager, ColorChosenResponse(decision.id, Color.GREEN)),
        ).result
        wrongSeat.error shouldNotBe null
        wrongSeat.state shouldBe restored.state
        var current = restored.state
        val manaEvents = mutableListOf<OtariaEncounterManaAddedEvent>()
        repeat(4) {
            val pending = current.pendingDecision as ChooseColorDecision
            val answered = processor.process(
                current, SubmitDecision(actorId, ColorChosenResponse(pending.id, Color.GREEN)),
            ).result
            answered.error shouldBe null
            val clientEvents = ClientEventTransformer.transform(answered.events, explorer, answered.state, visibility)
            json.encodeToString(clientEvents) shouldNotContain morph.name
            manaEvents.addAll(answered.events.filterIsInstance<OtariaEncounterManaAddedEvent>())
            current = json.decodeFromString(json.encodeToString(answered.state))
        }
        current.pendingDecision shouldBe null
        current.step shouldBe Step.OTARIA_EXPLORATION
        current.activePlayerId shouldBe explorer
        current.priorityPlayerId shouldBe explorer
        val members = requireNotNull(current.otaria).encounters.getValue(actorId).members
        manaEvents.groupingBy { it.creature }.eachCount() shouldBe members.associateWith { 2 }
        val pool = requireNotNull(current.getEntity(actorId)?.get<ManaPoolComponent>())
        pool.restrictedMana.groupingBy { it.assignedPermanent }.eachCount() shouldBe members.associateWith { 2 }
        pool.restrictedMana.all { it.remainsUntilSpent && it.color == Color.GREEN } shouldBe true
    }

    test("boss support forests are separate permanents assigned to the primary creature") {
        val support = OtariaEncounterSupport(listOf(
            OtariaReinforcementGroup(OtariaReinforcementContent.Card("Forest"), OtariaReinforcementCount.Fixed(6)),
        ))
        val source = OtariaExplorationSource.Star(OtariaLocation.KROSIAN_FOREST)
        val result = entry.start(initial(watcher, support, source))
        result.error shouldBe null
        val started = started(result)
        val encounter = requireNotNull(result.state.otaria).encounters.getValue(started.actorId)
        encounter.members.size shouldBe 7
        encounter.manaSources.size shouldBe 6
        encounter.manaSources.all { it.creature == started.primary } shouldBe true
        encounter.manaSources.forEach {
            OtariaEncounterMana.sourceOwner(result.state, started.actorId, it.source.entityId) shouldBe started.primary
        }
        val entries = result.events.filterIsInstance<ZoneChangeEvent>().filter { it.toZone == Zone.BATTLEFIELD }
        entries.size shouldBe 7
        entries.map { it.simultaneousEntryGroup }.distinct().size shouldBe 1
    }

    test("a reinforcement die is deterministic and respects existing face-up copies") {
        val support = OtariaEncounterSupport(listOf(OtariaReinforcementGroup(
            OtariaReinforcementContent.Card(legion.name), OtariaReinforcementCount.Die(4),
            maximumCopiesOnBattlefield = 4,
        )))
        var before = initial(legion, support)
        before = addPermanent(before, EntityId("existing-one"), players[2], legion)
        before = addPermanent(before, EntityId("existing-two"), players[2], legion)
        before = addPermanent(before, EntityId("hidden-existing"), players[2], legion)
            .updateEntity(EntityId("hidden-existing")) { it.with(FaceDownComponent) }
        val first = entry.start(before)
        val second = entry.start(before)
        first shouldBe second
        first.error shouldBe null
        val roll = first.events.filterIsInstance<OtariaReinforcementRolledEvent>().single().roll
        (roll.natural in 1..4) shouldBe true
        val encounter = started(first)
        encounter.members.size shouldBe 2
        first.state.rng shouldNotBe before.rng
        val serialized = json.encodeToString<GameEvent>(first.events.filterIsInstance<OtariaReinforcementRolledEvent>().single())
        json.decodeFromString<GameEvent>(serialized) shouldBe first.events.filterIsInstance<OtariaReinforcementRolledEvent>().single()
    }

    test("a face-down primary does not count towards its printed name's reinforcement cap") {
        val support = OtariaEncounterSupport(listOf(OtariaReinforcementGroup(
            OtariaReinforcementContent.Card(morph.name), OtariaReinforcementCount.Die(4),
            maximumCopiesOnBattlefield = 1,
        )))
        val result = entry.start(initial(morph, support))
        result.error shouldBe null
        val encounter = started(result)
        encounter.members.size shouldBe 2
        result.state.getEntity(encounter.primary.entityId)?.has<FaceDownComponent>() shouldBe true
        val other = encounter.members.single { it != encounter.primary }
        result.state.getEntity(other.entityId)?.get<CardComponent>()?.name shouldBe morph.name
    }

    test("reinforcements choose their colors independently before simultaneous entry") {
        val support = OtariaEncounterSupport(listOf(
            OtariaReinforcementGroup(OtariaReinforcementContent.Card(choice.name), OtariaReinforcementCount.Fixed(2)),
            OtariaReinforcementGroup(goblin, OtariaReinforcementCount.Die(4)),
            OtariaReinforcementGroup(
                OtariaReinforcementContent.Card(reserveSpell.name, OtariaReinforcementDestination.HAND),
                OtariaReinforcementCount.Fixed(1),
            ),
        ))
        val first = entry.start(initial(watcher, support))
        first.error shouldBe null
        (first.outcome is Outcome.Paused) shouldBe true
        first.state.getBattlefield() shouldBe emptyList()
        val actor = requireNotNull(first.state.otaria).encounters.keys.single()
        first.state.actorFor(actor) shouldBe manager
        first.events.filterIsInstance<OtariaReinforcementRolledEvent>().size shouldBe 1
        first.events.filterIsInstance<OtariaEncounterSupportHandCreatedEvent>().size shouldBe 1

        var current = json.decodeFromString<GameState>(json.encodeToString(first.state))
        val answered = mutableListOf<EntityId>()
        val colors = listOf(Color.BLUE, Color.RED)
        for ((index, color) in colors.withIndex()) {
            val question = current.pendingDecision as ChooseColorDecision
            question.playerId shouldBe actor
            answered.add(requireNotNull(question.context.sourceId))
            val wrongSeat = processor.process(current, SubmitDecision(manager, ColorChosenResponse(question.id, color))).result
            wrongSeat.error shouldNotBe null
            wrongSeat.state shouldBe current
            val result = processor.process(current, SubmitDecision(actor, ColorChosenResponse(question.id, color))).result
            result.error shouldBe null
            result.events.filterIsInstance<OtariaReinforcementRolledEvent>() shouldBe emptyList()
            result.events.filterIsInstance<OtariaEncounterSupportHandCreatedEvent>() shouldBe emptyList()
            current = json.decodeFromString(json.encodeToString(result.state))
            if (index == 0) current.getBattlefield() shouldBe emptyList()
        }
        answered.distinct().size shouldBe 2
        current.pendingDecision shouldBe null
        current.priorityPlayerId shouldBe explorer
        answered.zip(colors).forEach { (id, color) ->
            requireNotNull(current.getEntity(id)).chosenColor() shouldBe color
        }
        val encounter = requireNotNull(current.otaria).encounters.getValue(actor)
        encounter.members.map { it.entityId }.containsAll(answered) shouldBe true
    }

    test("a copy chooses only a nearby pre-entry permanent and then its copied entry color") {
        val near = EntityId("near-copy-source")
        val far = EntityId("far-copy-source")
        var before = addPermanent(initial(clone), near, explorer, choice)
        before = addPermanent(before, far, players[2], choice)
        val first = entry.start(before)
        first.error shouldBe null
        val actor = requireNotNull(first.state.otaria).encounters.keys.single()
        val copyQuestion = first.state.pendingDecision as SelectCardsDecision
        copyQuestion.playerId shouldBe actor
        copyQuestion.options shouldBe listOf(near)
        val copied = processor.process(
            json.decodeFromString(json.encodeToString(first.state)),
            SubmitDecision(actor, CardsSelectedResponse(copyQuestion.id, listOf(near))),
        ).result
        copied.error shouldBe null
        copied.state.getBattlefield().toSet() shouldBe setOf(near, far)
        val colorQuestion = copied.state.pendingDecision as ChooseColorDecision
        val final = processor.process(
            json.decodeFromString(json.encodeToString(copied.state)),
            SubmitDecision(actor, ColorChosenResponse(colorQuestion.id, Color.GREEN)),
        ).result
        final.error shouldBe null
        val primary = requireNotNull(final.state.otaria).encounters.getValue(actor).primaryId
        final.state.getEntity(primary)?.get<CardComponent>()?.name shouldBe choice.name
        requireNotNull(final.state.getEntity(primary)).chosenColor() shouldBe Color.GREEN
        requireNotNull(final.state.getEntity(near)).chosenColor() shouldBe null
        final.state.priorityPlayerId shouldBe explorer
    }

    test("entry triggers wait for encounter mana choices and are then put on the stack") {
        val support = OtariaEncounterSupport(listOf(OtariaReinforcementGroup(goblin, OtariaReinforcementCount.Fixed(1))))
        val before = addPermanent(initial(watcher, support), EntityId("explorer-land"), explorer, forest)
        val first = services.settler.settle(entry.start(before))
        first.error shouldBe null
        val actor = started(first).actorId
        first.state.pendingTriggers.size shouldBe 1
        first.state.stack shouldBe emptyList()
        var current = first.state
        repeat(2) {
            val question = current.pendingDecision as ChooseColorDecision
            val answer = processor.process(current, SubmitDecision(actor, ColorChosenResponse(question.id, Color.GREEN))).result
            answer.error shouldBe null
            current = answer.state
        }
        current.pendingDecision shouldBe null
        current.pendingTriggers shouldBe emptyList()
        current.stack.size shouldBe 1
        current.priorityPlayerId shouldBe explorer
    }

    test("missing support, invalid managers and unsupported entry choices reject atomically") {
        val missing = OtariaEncounterSupport(listOf(OtariaReinforcementGroup(
            OtariaReinforcementContent.Card("Unregistered support"), OtariaReinforcementCount.Fixed(1),
        )))
        val missingState = initial(watcher, missing)
        val failed = entry.start(missingState)
        failed.error shouldContain "support card not found"
        failed.state shouldBe missingState
        failed.events shouldBe emptyList()

        val wrongManager = initial(watcher, managerId = players[2])
        entry.start(wrongManager).error shouldContain "player to the explorer's left"
        entry.start(wrongManager).state shouldBe wrongManager

        val needsChoice = initial(unsupportedChoice)
        val rejected = entry.start(needsChoice)
        rejected.error shouldContain "entry choices are not implemented"
        rejected.state shouldBe needsChoice
        rejected.events shouldBe emptyList()
    }

    test("a creature controlled by another player is not granted encounter mana") {
        val withLand = addPermanent(initial(watcher), EntityId("land"), explorer, forest)
        val result = entry.start(withLand)
        val actor = started(result).actorId
        val creature = started(result).primary
        val stolen = result.state.updateEntity(creature.entityId) { it.with(ControllerComponent(players[2])) }
        OtariaEncounterMana.needed(stolen, actor, creature) shouldBe 0
        OtariaEncounterMana.add(stolen, actor, creature, Color.GREEN).error shouldContain "limit"
    }
})
