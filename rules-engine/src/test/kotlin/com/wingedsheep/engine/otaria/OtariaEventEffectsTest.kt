package com.wingedsheep.engine.otaria

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.mechanics.layers.Layer
import com.wingedsheep.engine.mechanics.layers.SerializableModification
import com.wingedsheep.engine.mechanics.layers.StaticAbilityHandler
import com.wingedsheep.engine.mechanics.layers.addFloatingEffect
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.DamageComponent
import com.wingedsheep.engine.state.components.identity.LifeTotalComponent
import com.wingedsheep.engine.state.components.identity.PlayerComponent
import com.wingedsheep.engine.state.components.identity.TokenComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.mtg.sets.otaria.OtariaEventEffects
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.DoubleDamage
import com.wingedsheep.sdk.scripting.Duration
import com.wingedsheep.sdk.scripting.EventPattern
import com.wingedsheep.sdk.scripting.HalveDamage
import com.wingedsheep.sdk.scripting.ModifyKeywordActionAmount
import com.wingedsheep.sdk.scripting.references.Player
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class OtariaEventEffectsTest : FunSpec({
    val players = (1..3).map { EntityId("event-player-$it") }
    val (explorer, manager, distantPlayer) = players
    val sturdy = card("Event Sturdy") {
        manaCost = "{0}"
        typeLine = "Creature — Human"
        power = 4
        toughness = 5
    }
    val small = card("Event Small") {
        manaCost = "{0}"
        typeLine = "Creature — Human"
        power = 2
        toughness = 5
    }
    val artifact = card("Event Artifact") {
        manaCost = "{0}"
        typeLine = "Artifact"
    }
    val shroudedArtifact = card("Event Shrouded Artifact") {
        manaCost = "{0}"
        typeLine = "Artifact"
        keywords(Keyword.SHROUD)
    }
    val enchantment = card("Event Enchantment") {
        manaCost = "{0}"
        typeLine = "Enchantment"
    }
    val doubler = card("Event Damage Doubler") {
        manaCost = "{0}"
        typeLine = "Enchantment"
        replacementEffect(DoubleDamage(appliesTo = EventPattern.DamageEvent()))
    }
    val halver = card("Event Damage Halver") {
        manaCost = "{0}"
        typeLine = "Enchantment"
        replacementEffect(HalveDamage(appliesTo = EventPattern.DamageEvent()))
    }
    val millDoubler = card("Event Mill Doubler") {
        manaCost = "{0}"
        typeLine = "Artifact"
        replacementEffect(ModifyKeywordActionAmount(EventPattern.MillEvent(Player.Each), multiplier = 2))
    }
    val millPlusOne = card("Event Mill Plus One") {
        manaCost = "{0}"
        typeLine = "Artifact"
        replacementEffect(ModifyKeywordActionAmount(EventPattern.MillEvent(Player.Each), modifier = 1))
    }
    val registry = CardRegistry().also { registry ->
        listOf(sturdy, small, artifact, shroudedArtifact, enchantment, doubler, halver, millDoubler, millPlusOne)
            .forEach(registry::register)
        registry.register(CardDefinition.basicLand("Forest", Subtype.FOREST))
    }
    val services = EngineServices(registry)
    val processor = ActionProcessor(services)
    val json = Json { serializersModule = engineSerializersModule; allowStructuredMapKeys = true; encodeDefaults = true }

    fun game(eventName: String): GameState {
        val event = OtariaPileCard(
            eventName,
            OtariaCardKind.EVENT,
            OtariaEventEffects.forName(eventName),
            if (eventName == "Ev. Blocage cérébral") OtariaEventCopyRule.EACH_SPELL_CAST_THIS_TURN else null,
        )
        val positions = listOf(OtariaPosition(6, 5), OtariaPosition(7, 5), OtariaPosition(10, 11))
        val mode = OtariaState(
            players = players.indices.associate { index ->
                players[index] to OtariaPlayerState(OtariaLocation.entries[index], positions[index])
            },
            explorationsUsed = OtariaLocation.entries.associateWith { 0 },
            starPile = OtariaExplorationPile(emptyList()),
            colorPiles = Color.entries.associateWith {
                OtariaExplorationPile(if (it == Color.GREEN) listOf(event) else emptyList())
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
            phase = Step.PRECOMBAT_MAIN.phase,
            otaria = mode,
        )
    }

    fun put(state: GameState, id: EntityId, owner: EntityId, definition: CardDefinition): GameState {
        val statics = StaticAbilityHandler(registry)
        val entity = CardEntityFactory.create(definition, owner)
            .let { statics.addContinuousEffectComponent(it, definition) }
            .let { statics.addReplacementEffectComponent(it, definition) }
        return state.withEntity(id, entity)
            .addToZone(ZoneKey(owner, Zone.BATTLEFIELD), id)
    }

    fun withLibrary(state: GameState, count: Int): GameState {
        var current = state
        repeat(count) { index ->
            val id = EntityId("library-card-$index")
            current = current.withEntity(id, CardEntityFactory.create(small, explorer))
                .addToZone(ZoneKey(explorer, Zone.LIBRARY), id)
        }
        return current
    }

    fun explore(state: GameState): ExecutionResult {
        val entered = services.turnManager.advanceStep(state)
        val decision = entered.state.pendingDecision as ChooseOptionDecision
        return processor.process(
            entered.state, SubmitDecision(explorer, OptionChosenResponse(decision.id, 1)),
        ).result
    }

    fun assertFinished(result: ExecutionResult, name: String) {
        result.error shouldBe null
        val mode = requireNotNull(result.state.otaria)
        mode.pendingExploration shouldBe null
        mode.turn?.explorationFinished shouldBe true
        mode.colorPiles.getValue(Color.GREEN).discard.single().name shouldBe name
        result.events.filterIsInstance<OtariaEventFinishedEvent>().single().cardName shouldBe name
        result.events.filterIsInstance<SpellCastEvent>().size shouldBe 0
        services.turnManager.advanceStep(result.state).state.step shouldBe Step.POSTCOMBAT_MAIN
    }

    test("naturalisation lets the left player choose a controlled artifact or enchantment without targeting") {
        val name = "Ev. Naturalisation"
        val first = EntityId("artifact-one")
        val second = EntityId("enchantment-one")
        val other = EntityId("artifact-other")
        val initial = put(put(put(game(name), first, explorer, shroudedArtifact), second, explorer, enchantment),
            other, manager, artifact)
        val pending = explore(initial)
        val decision = pending.state.pendingDecision as SelectCardsDecision
        decision.playerId shouldBe manager
        decision.options.shouldContainExactlyInAnyOrder(first, second)
        decision.useTargetingUI shouldBe true
        pending.events.filterIsInstance<CardsRevealedEvent>().single().cardNames shouldBe listOf(name)
        pending.events.filterIsInstance<CardsRevealedEvent>().single().imageUris shouldBe
            listOf("/images/otaria/events/naturalisation.png")
        pending.state.otaria?.colorPiles?.get(Color.GREEN)?.discard shouldBe emptyList()

        val restored = json.decodeFromString<ExecutionResult>(json.encodeToString(pending))
        val wrongPlayer = processor.process(
            restored.state, SubmitDecision(explorer, CardsSelectedResponse(decision.id, listOf(first))),
        ).result
        wrongPlayer.error shouldNotBe null
        wrongPlayer.state shouldBe restored.state
        val resolved = processor.process(
            restored.state, SubmitDecision(manager, CardsSelectedResponse(decision.id, listOf(first))),
        ).result
        assertFinished(resolved, name)
        resolved.state.getZone(explorer, Zone.GRAVEYARD) shouldBe listOf(first)
        (second in resolved.state.getBattlefield()) shouldBe true
        (other in resolved.state.getBattlefield()) shouldBe true
    }

    test("concerted strike uses projected power and lets the manager exile one eligible creature") {
        val name = "Ev. Frappe Concertée"
        val first = EntityId("small-one")
        val second = EntityId("small-two")
        val large = EntityId("large")
        val initial = put(put(put(game(name), first, explorer, small), second, explorer, small),
            large, explorer, sturdy)
            .addFloatingEffect(
                layer = Layer.POWER_TOUGHNESS,
                modification = SerializableModification.ModifyPowerToughness(-2, 0),
                affectedEntities = setOf(large),
                duration = Duration.EndOfTurn,
                context = EffectContext(large, explorer),
            )
        val pending = explore(initial)
        val decision = pending.state.pendingDecision as SelectCardsDecision
        decision.playerId shouldBe manager
        decision.options.shouldContainExactlyInAnyOrder(first, second, large)
        val resolved = processor.process(
            pending.state, SubmitDecision(manager, CardsSelectedResponse(decision.id, listOf(second))),
        ).result
        assertFinished(resolved, name)
        resolved.state.getZone(explorer, Zone.EXILE) shouldBe listOf(second)
        (large in resolved.state.getBattlefield()) shouldBe true
    }

    test("a choice event with no eligible permanent finishes without asking") {
        val name = "Ev. Naturalisation"
        assertFinished(explore(game(name)), name)
    }

    test("an unsupported merchant is revealed with its own proxy but skipped without applying effects") {
        val name = "Ev. Marchant itinérant G3"
        val result = explore(game(name))
        result.error shouldBe null
        result.state.otaria?.pendingExploration shouldBe null
        result.state.otaria?.turn?.explorationFinished shouldBe true
        result.state.otaria?.colorPiles?.getValue(Color.GREEN)?.discard?.single()?.name shouldBe name
        val reveal = result.events.filterIsInstance<CardsRevealedEvent>().single()
        reveal.cardNames shouldBe listOf(name)
        reveal.imageUris shouldBe listOf("/images/otaria/events/merchant-g3.png")
        result.state.getEntity(reveal.cardIds.single()) shouldBe null
        result.events.filterIsInstance<OtariaCardSkippedEvent>().single().cardName shouldBe name
        result.events.filterIsInstance<OtariaEventFinishedEvent>() shouldBe emptyList()
    }

    test("an unsupported event without artwork is skipped without an unusable reveal") {
        val result = explore(game("Ev. Unknown"))
        result.error shouldBe null
        result.state.otaria?.pendingExploration shouldBe null
        result.events.filterIsInstance<CardsRevealedEvent>() shouldBe emptyList()
        result.events.filterIsInstance<OtariaCardSkippedEvent>().single().cardName shouldBe "Ev. Unknown"
    }

    test("infestation affects creatures in range until end of turn") {
        val name = "Ev. Infestation"
        val own = EntityId("own-creature")
        val nearby = EntityId("nearby-creature")
        val distant = EntityId("distant-creature")
        val initial = put(put(put(game(name), own, explorer, sturdy), nearby, manager, sturdy),
            distant, distantPlayer, sturdy)
        val resolved = explore(initial)
        assertFinished(resolved, name)
        resolved.state.projectedState.getPower(own) shouldBe 2
        resolved.state.projectedState.getToughness(nearby) shouldBe 3
        resolved.state.projectedState.getPower(distant) shouldBe 4
        val cleaned = services.turnManager.cleanupPhaseManager.cleanupEndOfTurn(resolved.state)
        cleaned.projectedState.getPower(own) shouldBe 4
        cleaned.projectedState.getToughness(nearby) shouldBe 5
    }

    test("pyroclasm deals simultaneous damage in range from the event rather than the explorer") {
        val name = "Ev. Pyroclasme"
        val own = EntityId("own-creature")
        val nearby = EntityId("nearby-creature")
        val distant = EntityId("distant-creature")
        val initial = put(put(put(game(name), own, explorer, sturdy), nearby, manager, sturdy),
            distant, distantPlayer, sturdy)
        val resolved = explore(initial)
        assertFinished(resolved, name)
        val damage = resolved.events.filterIsInstance<DamageDealtEvent>()
        damage.map { it.targetId }.shouldContainExactlyInAnyOrder(own, nearby)
        damage.map { it.sourceName }.distinct() shouldBe listOf(name)
        damage.map { it.sourceId }.distinct().size shouldBe 1
        damage.all { it.sourceLastKnown?.controllerId == null } shouldBe true
        resolved.state.getEntity(own)?.get<DamageComponent>()?.amount shouldBe 2
        resolved.state.getEntity(distant)?.get<DamageComponent>() shouldBe null
    }

    test("damage replacement decisions can pause and restore event resolution") {
        val name = "Ev. Pyroclasme"
        val target = EntityId("replacement-target")
        val first = EntityId("doubler")
        val second = EntityId("halver")
        val initial = put(put(put(game(name), target, explorer, sturdy), first, explorer, doubler),
            second, explorer, halver)
        val pending = explore(initial)
        val decision = pending.state.pendingDecision as ChooseOptionDecision
        decision.playerId shouldBe explorer
        pending.state.otaria?.pendingExploration?.card?.name shouldBe name
        pending.state.otaria?.colorPiles?.get(Color.GREEN)?.discard shouldBe emptyList()
        val restored = json.decodeFromString<ExecutionResult>(json.encodeToString(pending))
        val resolved = processor.process(
            restored.state, SubmitDecision(explorer, OptionChosenResponse(decision.id, 0)),
        ).result
        assertFinished(resolved, name)
        resolved.events.filterIsInstance<DamageDealtEvent>().single().targetId shouldBe target
    }

    test("brain freeze makes distinct event tokens and mills separately for each prior spell") {
        val name = "Ev. Blocage cérébral"
        val initial = withLibrary(game(name).copy(spellsCastThisTurn = 2), 10)
        val resolved = explore(initial)
        assertFinished(resolved, name)
        val created = resolved.events.filterIsInstance<OtariaEventCopiesCreatedEvent>().single()
        created.cardName shouldBe name
        created.copySourceIds.distinct().size shouldBe 2
        (created.originalSourceId in created.copySourceIds) shouldBe false
        resolved.events.filterIsInstance<OtariaEventCopyFinishedEvent>().map { it.sourceId }
            .shouldContainExactlyInAnyOrder(created.copySourceIds)
        resolved.events.filterIsInstance<CardsMilledEvent>().map { it.cardIds.size } shouldBe listOf(3, 3, 3)
        resolved.state.getZone(explorer, Zone.GRAVEYARD).size shouldBe 9
        resolved.state.getZone(explorer, Zone.LIBRARY).size shouldBe 1
        resolved.state.spellsCastThisTurn shouldBe 2
        (created.copySourceIds + created.originalSourceId).all { resolved.state.getEntity(it) == null } shouldBe true
    }

    test("brain freeze without prior spells resolves once even with fewer than three cards") {
        val name = "Ev. Blocage cérébral"
        val resolved = explore(withLibrary(game(name), 2))
        assertFinished(resolved, name)
        resolved.events.filterIsInstance<OtariaEventCopiesCreatedEvent>() shouldBe emptyList()
        resolved.events.filterIsInstance<CardsMilledEvent>().single().cardIds.size shouldBe 2
        resolved.state.gameOver shouldBe false
    }

    test("brain freeze copies resume independently across serialized mill replacement decisions") {
        val name = "Ev. Blocage cérébral"
        val firstHost = EntityId("mill-doubler")
        val secondHost = EntityId("mill-plus-one")
        val initial = put(
            put(withLibrary(game(name).copy(spellsCastThisTurn = 1), 40), firstHost, explorer, millDoubler),
            secondHost, explorer, millPlusOne,
        )
        val first = explore(initial)
        val created = first.events.filterIsInstance<OtariaEventCopiesCreatedEvent>().single()
        val copyId = created.copySourceIds.single()
        first.state.getEntity(copyId)?.has<TokenComponent>() shouldBe true
        first.state.pendingDecision?.playerId shouldBe explorer
        first.state.otaria?.colorPiles?.get(Color.GREEN)?.discard shouldBe emptyList()
        val events = first.events.toMutableList()
        var state = json.decodeFromString<ExecutionResult>(json.encodeToString(first)).state
        var last = first
        var decisions = 0
        while (state.pendingDecision != null) {
            val decision = state.pendingDecision as ChooseOptionDecision
            decision.playerId shouldBe explorer
            last = processor.process(
                state, SubmitDecision(explorer, OptionChosenResponse(decision.id, 0)),
            ).result
            last.error shouldBe null
            events.addAll(last.events)
            state = json.decodeFromString<ExecutionResult>(json.encodeToString(last)).state
            decisions++
            check(decisions <= 2)
        }
        decisions shouldBe 2
        assertFinished(last, name)
        events.filterIsInstance<CardsMilledEvent>().size shouldBe 2
        events.filterIsInstance<OtariaEventCopyFinishedEvent>().single().sourceId shouldBe copyId
        state.getEntity(copyId) shouldBe null
        state.spellsCastThisTurn shouldBe 1
    }

    test("an unscripted event is discarded explicitly and ends the exploration instead of blocking") {
        val initial = game("Unsupported event")
        val entered = services.turnManager.advanceStep(initial)
        val decision = entered.state.pendingDecision as ChooseOptionDecision
        val result = processor.process(
            entered.state, SubmitDecision(explorer, OptionChosenResponse(decision.id, 1)),
        ).result
        result.error shouldBe null
        val mode = requireNotNull(result.state.otaria)
        mode.pendingExploration shouldBe null
        mode.turn?.explorationFinished shouldBe true
        mode.colorPiles.getValue(Color.GREEN).discard.single().name shouldBe "Unsupported event"
        mode.explorationsUsed.values.sum() shouldBe 0
        result.events.filterIsInstance<OtariaCardSkippedEvent>().single().cardName shouldBe "Unsupported event"
        result.events.filterIsInstance<OtariaEventFinishedEvent>() shouldBe emptyList()
        services.turnManager.advanceStep(result.state).state.step shouldBe Step.POSTCOMBAT_MAIN
    }
})
