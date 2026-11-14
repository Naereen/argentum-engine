package com.wingedsheep.engine.otaria

import com.wingedsheep.engine.core.ActionProcessor
import com.wingedsheep.engine.core.CardEntityFactory
import com.wingedsheep.engine.core.EngineServices
import com.wingedsheep.engine.core.ExecutionResult
import com.wingedsheep.engine.core.LibraryShuffledEvent
import com.wingedsheep.engine.core.OtariaPreparedEvent
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.ShuffleCause
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.engine.core.revealsInformation
import com.wingedsheep.engine.event.TriggerMatcher
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.PipelineState
import com.wingedsheep.engine.handlers.PredicateEvaluator
import com.wingedsheep.engine.handlers.effects.LibraryPlacement
import com.wingedsheep.engine.handlers.effects.ZoneEntryOptions
import com.wingedsheep.engine.handlers.effects.ZoneTransitionService
import com.wingedsheep.engine.handlers.effects.library.GatherCardsExecutor
import com.wingedsheep.engine.handlers.effects.library.LibraryRevealUtils
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.LibraryPositionAnchor
import com.wingedsheep.engine.state.components.identity.OwnerComponent
import com.wingedsheep.engine.state.components.identity.PlayerComponent
import com.wingedsheep.engine.state.components.identity.RevealedToComponent
import com.wingedsheep.engine.state.components.identity.TokenComponent
import com.wingedsheep.engine.view.ClientEvent
import com.wingedsheep.engine.view.ClientEventTransformer
import com.wingedsheep.engine.view.ClientStateTransformer
import com.wingedsheep.engine.view.Visibility
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.model.GameRng
import com.wingedsheep.sdk.scripting.EventPattern
import com.wingedsheep.sdk.scripting.LookAtTopOfLibrary
import com.wingedsheep.sdk.scripting.PlayersRevealTopOfLibrary
import com.wingedsheep.sdk.scripting.TriggerBinding
import com.wingedsheep.sdk.scripting.effects.CardDestination
import com.wingedsheep.sdk.scripting.effects.CardSource
import com.wingedsheep.sdk.scripting.effects.CompositeEffect
import com.wingedsheep.sdk.scripting.effects.GatherCardsEffect
import com.wingedsheep.sdk.scripting.effects.MoveCollectionEffect
import com.wingedsheep.sdk.scripting.effects.ZonePlacement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class OtariaPreparationTest : FunSpec({
    val p1 = EntityId("player-1")
    val p2 = EntityId("player-2")
    val p3 = EntityId("player-3")
    val players = listOf(p1, p2, p3)
    val registry = CardRegistry()
    val evaluator = PredicateEvaluator(registry)
    val preparation = OtariaPreparation(registry, evaluator)
    val visibility = Visibility(registry, conditionEvaluator = evaluator.conditions)
    val libraryZone = ZoneKey(p1, Zone.LIBRARY)
    fun id(name: String) = EntityId(name)

    fun cardEntity(name: String, owner: EntityId = p1): ComponentContainer = ComponentContainer.of(
        CardComponent(
            cardDefinitionId = name,
            name = name,
            manaCost = ManaCost(emptyList()),
            typeLine = TypeLine(cardTypes = setOf(CardType.ARTIFACT)),
            ownerId = owner,
        ),
        OwnerComponent(owner),
    )

    fun game(library: List<EntityId>, reserve: List<EntityId> = emptyList()): GameState {
        val mode = OtariaState(
            players = players.zip(OtariaLocation.entries).associate { (player, start) ->
                player to OtariaPlayerState(start, start.position, reserve = if (player == p1) reserve else emptyList())
            },
            explorationsUsed = OtariaLocation.entries.associateWith { 0 },
            starPile = OtariaExplorationPile(emptyList()),
            colorPiles = Color.entries.associateWith { OtariaExplorationPile(emptyList()) },
            treasurePile = emptyList(),
            locationPile = emptyList(),
        )
        var state = GameState(
            entities = players.associateWith { ComponentContainer.of(PlayerComponent(it.value)) },
            turnOrder = players,
            activePlayerId = p1,
            priorityPlayerId = p1,
            rng = GameRng(1234),
            otaria = mode,
        )
        for (cardId in library + reserve) state = state.withEntity(cardId, cardEntity(cardId.value))
        for (cardId in library) state = state.addToZone(libraryZone, cardId)
        return state
    }

    fun reveal(
        state: GameState,
        cardId: EntityId,
        viewers: Set<EntityId> = setOf(p1),
        anchor: LibraryPositionAnchor? = null,
    ): GameState = LibraryRevealUtils.markRevealed(state, listOf(cardId), viewers, anchor)

    fun anchor(state: GameState, cardId: EntityId, player: EntityId = p1): LibraryPositionAnchor? =
        state.getEntity(cardId)?.get<RevealedToComponent>()?.libraryPositionAnchors?.get(player)

    test("selected reserve cards join a deterministic shuffle without leaking their identities") {
        val library = listOf(id("a"), id("b"), id("c"))
        val selected = id("selected")
        val retained = id("retained")
        val initial = game(library, listOf(selected, retained))
        val prepared = preparation.prepare(initial, p1, listOf(selected))
        prepared.error shouldBe null
        prepared shouldBe preparation.prepare(initial, p1, listOf(selected))
        initial.getZone(libraryZone) shouldBe library
        requireNotNull(initial.otaria).players.getValue(p1).reserve shouldBe listOf(selected, retained)
        prepared.state.getZone(libraryZone).toSet() shouldBe (library + selected).toSet()
        requireNotNull(prepared.state.otaria).players.getValue(p1).reserve shouldBe listOf(retained)
        prepared.state.rng shouldNotBe initial.rng
        prepared.events shouldBe listOf(
            OtariaPreparedEvent(p1, listOf(selected)),
            LibraryShuffledEvent(p1, ShuffleCause.OTARIA_PREPARATION),
        )
        prepared.events.first().revealsInformation shouldBe true
        val clientEvents = ClientEventTransformer.transform(prepared.events, p2, prepared.state)
        clientEvents.size shouldBe 1
        (clientEvents.single() is ClientEvent.LibraryShuffled) shouldBe true
        val opponentView = ClientStateTransformer(registry, predicateEvaluator = evaluator).transform(prepared.state, p2)
        opponentView.cards.keys shouldNotContain selected
        visibility.isCardIdentityVisibleTo(prepared.state, libraryZone, selected, p1) shouldBe false
    }

    test("buried top and bottom positions survive while unanchored middle knowledge is forgotten") {
        val cards = (0..6).map { id("library-$it") }
        val extra = listOf(id("reserve-1"), id("reserve-2"))
        var initial = game(cards, extra)
        initial = reveal(initial, cards[1], setOf(p1, p2), LibraryPositionAnchor.TOP)
        initial = reveal(initial, cards[3])
        initial = reveal(initial, cards[5], setOf(p1), LibraryPositionAnchor.BOTTOM)
        initial = reveal(initial, cards[0], setOf(p3), LibraryPositionAnchor.TOP)

        val prepared = preparation.prepare(initial, p1, extra)
        prepared.error shouldBe null
        val after = prepared.state.getZone(libraryZone)
        after[1] shouldBe cards[1]
        after[7] shouldBe cards[5]
        after.toSet() shouldBe (cards + extra).toSet()
        prepared.state.getEntity(cards[1])?.get<RevealedToComponent>()?.playerIds shouldBe setOf(p1, p2)
        prepared.state.getEntity(cards[3])?.get<RevealedToComponent>() shouldBe null
        prepared.state.getEntity(cards[0])?.get<RevealedToComponent>() shouldBe null
        anchor(prepared.state, cards[1]) shouldBe LibraryPositionAnchor.TOP
        anchor(prepared.state, cards[5]) shouldBe LibraryPositionAnchor.BOTTOM
        initial.getEntity(cards[3])?.get<RevealedToComponent>() shouldNotBe null
    }

    test("a completely known library remains above newly added cards in its original order") {
        val cards = (0..3).map { id("known-$it") }
        val extra = listOf(id("extra-1"), id("extra-2"), id("extra-3"))
        var initial = game(cards, extra)
        for ((index, cardId) in cards.withIndex()) {
            initial = reveal(initial, cardId, anchor = if (index >= 2) LibraryPositionAnchor.BOTTOM else null)
        }
        val prepared = preparation.prepare(initial, p1, extra)
        prepared.error shouldBe null
        val after = prepared.state.getZone(libraryZone)
        after.take(cards.size) shouldBe cards
        after.drop(cards.size).toSet() shouldBe extra.toSet()
        cards.forEachIndexed { index, cardId ->
            anchor(prepared.state, cardId) shouldBe
                if (index >= 2) LibraryPositionAnchor.BOTH else LibraryPositionAnchor.TOP
        }
        val orders = (1L..16L).map { seed ->
            preparation.prepare(initial.copy(rng = GameRng(seed)), p1, extra)
                .state.getZone(libraryZone).drop(cards.size)
        }.toSet()
        (orders.size > 1) shouldBe true
    }

    test("the empty library and a preparation without additions still shuffle correctly") {
        val reserve = listOf(id("a"), id("b"), id("c"))
        val empty = preparation.prepare(game(emptyList(), reserve), p1, reserve)
        empty.error shouldBe null
        empty.state.getZone(libraryZone).toSet() shouldBe reserve.toSet()
        requireNotNull(empty.state.otaria).players.getValue(p1).reserve shouldBe emptyList()

        val initial = game(reserve)
        val noAddition = preparation.prepare(initial, p1, emptyList())
        noAddition.error shouldBe null
        noAddition.state.getZone(libraryZone).toSet() shouldBe reserve.toSet()
        noAddition.state.rng shouldNotBe initial.rng
        noAddition.events.last() shouldBe LibraryShuffledEvent(p1, ShuffleCause.OTARIA_PREPARATION)
    }

    test("revealed cards at the top and bottom retain their positions without stored provenance") {
        val cards = (0..3).map { id("edge-$it") }
        val extra = id("extra")
        var initial = game(cards, listOf(extra))
        initial = reveal(initial, cards.first())
        initial = reveal(initial, cards.last())
        val prepared = preparation.prepare(initial, p1, listOf(extra))
        prepared.error shouldBe null
        prepared.state.getZone(libraryZone).first() shouldBe cards.first()
        prepared.state.getZone(libraryZone).last() shouldBe cards.last()
    }

    test("actual top and bottom look effects remember their origin even if other cards bury them") {
        val cards = (0..3).map { id("look-$it") }
        val extra = id("extra")
        val gather = GatherCardsExecutor(evaluator, registry)
        val context = EffectContext(sourceId = null, controllerId = p1)
        var initial = game(cards, listOf(extra))
        initial = gather.execute(
            initial, GatherCardsEffect(CardSource.TopOfLibrary(1), storeAs = "top"), context,
        ).state
        initial = gather.execute(
            initial, GatherCardsEffect(CardSource.BottomOfLibrary(1), storeAs = "bottom"), context,
        ).state
        anchor(initial, cards.first()) shouldBe LibraryPositionAnchor.TOP
        anchor(initial, cards.last()) shouldBe LibraryPositionAnchor.BOTTOM
        val newTop = id("unknown-top")
        val newBottom = id("unknown-bottom")
        initial = initial.withEntity(newTop, cardEntity(newTop.value)).insertIntoZone(libraryZone, newTop, 0)
        initial = initial.withEntity(newBottom, cardEntity(newBottom.value)).addToZone(libraryZone, newBottom)
        val before = initial.getZone(libraryZone)
        val prepared = preparation.prepare(initial, p1, listOf(extra))
        prepared.error shouldBe null
        prepared.state.getZone(libraryZone)[before.indexOf(cards.first())] shouldBe cards.first()
        prepared.state.getZone(libraryZone)[before.indexOf(cards.last()) + 1] shouldBe cards.last()
    }

    test("a card seen from both ends retains its top position regardless of look order") {
        val cards = (0..4).map { id("both-$it") }
        val extra = id("both-extra")
        val gather = GatherCardsExecutor(evaluator, registry)
        val context = EffectContext(sourceId = null, controllerId = p1)
        var initial = game(cards, listOf(extra))
        initial = gather.execute(
            initial, GatherCardsEffect(CardSource.TopOfLibrary(1), storeAs = "top"), context,
        ).state
        for (index in 0..1) {
            val newTop = id("unknown-$index")
            initial = initial.withEntity(newTop, cardEntity(newTop.value)).insertIntoZone(libraryZone, newTop, 0)
        }
        initial = gather.execute(
            initial, GatherCardsEffect(CardSource.BottomOfLibrary(5), storeAs = "bottom"), context,
        ).state
        initial = reveal(initial, cards.first(), setOf(p2), LibraryPositionAnchor.BOTTOM)
        initial = reveal(initial, cards.first(), setOf(p2), LibraryPositionAnchor.TOP)
        anchor(initial, cards.first(), p1) shouldBe LibraryPositionAnchor.BOTH
        anchor(initial, cards.first(), p2) shouldBe LibraryPositionAnchor.BOTH
        val result = preparation.prepare(initial, p1, listOf(extra))
        result.error shouldBe null
        result.state.getZone(libraryZone)[2] shouldBe cards.first()
        visibility.isCardIdentityVisibleTo(result.state, libraryZone, cards.first(), p1) shouldBe true
        visibility.isCardIdentityVisibleTo(result.state, libraryZone, cards.first(), p2) shouldBe true
    }

    test("known placements through the real zone service retain top or bottom provenance") {
        val cards = (0..2).map { id("zone-$it") }
        val top = id("top-from-hand")
        val bottom = id("bottom-from-hand")
        val extra = id("extra")
        var initial = game(cards, listOf(extra))
        initial = initial.withEntity(top, cardEntity(top.value)).addToZone(ZoneKey(p1, Zone.HAND), top)
        initial = initial.withEntity(bottom, cardEntity(bottom.value)).addToZone(ZoneKey(p1, Zone.HAND), bottom)
        val zones = ZoneTransitionService(registry, evaluator)
        initial = zones.moveToZone(
            initial, top, Zone.LIBRARY,
            ZoneEntryOptions(libraryPlacement = LibraryPlacement.Top, libraryMoverId = p1),
        ).state
        initial = zones.moveToZone(
            initial, bottom, Zone.LIBRARY,
            ZoneEntryOptions(libraryPlacement = LibraryPlacement.Bottom, libraryMoverId = p1),
        ).state
        anchor(initial, top) shouldBe LibraryPositionAnchor.TOP
        anchor(initial, bottom) shouldBe LibraryPositionAnchor.BOTTOM
        val hidden = id("hidden-top")
        initial = initial.withEntity(hidden, cardEntity(hidden.value)).insertIntoZone(libraryZone, hidden, 0)
        val before = initial.getZone(libraryZone)
        val prepared = preparation.prepare(initial, p1, listOf(extra))
        prepared.error shouldBe null
        prepared.state.getZone(libraryZone)[before.indexOf(top)] shouldBe top
        prepared.state.getZone(libraryZone).last() shouldBe bottom
        prepared.state.getEntity(top)?.get<RevealedToComponent>()?.playerIds shouldBe setOf(p1)
    }

    test("a static private look at the top is preserved without revealing it to opponents") {
        val lens = card("Otaria Test Lens") {
            manaCost = "{0}"
            typeLine = "Artifact"
            staticAbility { ability = LookAtTopOfLibrary() }
        }
        registry.register(lens)
        val lensId = id("lens")
        val cards = (0..2).map { id("private-$it") }
        val extra = id("extra")
        var initial = game(cards, listOf(extra))
        initial = initial.withEntity(lensId, CardEntityFactory.create(lens, p1))
            .addToZone(ZoneKey(p1, Zone.BATTLEFIELD), lensId)
        visibility.isCardIdentityVisibleTo(initial, libraryZone, cards.first(), p1) shouldBe true
        visibility.isCardIdentityVisibleTo(initial, libraryZone, cards.first(), p2) shouldBe false
        val prepared = preparation.prepare(initial, p1, listOf(extra))
        prepared.error shouldBe null
        prepared.state.getZone(libraryZone).first() shouldBe cards.first()
        val afterLensLeaves = prepared.state.removeFromZone(ZoneKey(p1, Zone.BATTLEFIELD), lensId)
        visibility.isCardIdentityVisibleTo(afterLensLeaves, libraryZone, cards.first(), p1) shouldBe true
        visibility.isCardIdentityVisibleTo(afterLensLeaves, libraryZone, cards.first(), p2) shouldBe false
        anchor(afterLensLeaves, cards.first()) shouldBe LibraryPositionAnchor.TOP
    }

    test("an action remembers a static top before it is buried or its source leaves") {
        val lens = card("Otaria Action Lens") {
            manaCost = "{0}"
            typeLine = "Artifact"
            staticAbility { ability = LookAtTopOfLibrary() }
        }
        registry.register(lens)
        val lensId = id("action-lens")
        val cards = (0..2).map { id("action-$it") }
        val extra = id("extra")
        var initial = game(cards, listOf(extra))
        initial = initial.withEntity(lensId, CardEntityFactory.create(lens, p1))
            .addToZone(ZoneKey(p1, Zone.BATTLEFIELD), lensId)
        val action = ActionProcessor(EngineServices(registry)).process(initial, PassPriority(p1)).result
        action.error shouldBe null
        anchor(action.state, cards.first()) shouldBe LibraryPositionAnchor.TOP
        var buried = action.state.removeFromZone(ZoneKey(p1, Zone.BATTLEFIELD), lensId)
        val hidden = id("new-top")
        buried = buried.withEntity(hidden, cardEntity(hidden.value)).insertIntoZone(libraryZone, hidden, 0)
        val prepared = preparation.prepare(buried, p1, listOf(extra))
        prepared.error shouldBe null
        prepared.state.getZone(libraryZone)[1] shouldBe cards.first()
        visibility.isCardIdentityVisibleTo(prepared.state, libraryZone, cards.first(), p1) shouldBe true
        visibility.isCardIdentityVisibleTo(prepared.state, libraryZone, cards.first(), p2) shouldBe false
    }

    test("a publicly visible top is remembered for all players without repeated state copies") {
        val lens = card("Otaria Public Lens") {
            manaCost = "{0}"
            typeLine = "Artifact"
            staticAbility { ability = PlayersRevealTopOfLibrary }
        }
        registry.register(lens)
        val lensId = id("public-lens")
        val cards = (0..2).map { id("public-$it") }
        val initial = game(cards).withEntity(lensId, CardEntityFactory.create(lens, p1))
            .addToZone(ZoneKey(p1, Zone.BATTLEFIELD), lensId)
        val knowledge = OtariaLibraryKnowledge(registry, evaluator)
        val remembered = knowledge.rememberVisibleTops(initial)
        for (player in players) {
            anchor(remembered, cards.first(), player) shouldBe LibraryPositionAnchor.TOP
        }
        (knowledge.rememberVisibleTops(remembered) === remembered) shouldBe true
        val withoutLens = remembered.removeFromZone(ZoneKey(p1, Zone.BATTLEFIELD), lensId)
        for (player in players) {
            visibility.isCardIdentityVisibleTo(withoutLens, libraryZone, cards.first(), player) shouldBe true
        }
    }

    test("a composite remembers a card exposed between instructions before it is buried") {
        val lens = card("Otaria Composite Lens") {
            manaCost = "{0}"
            typeLine = "Artifact"
            staticAbility { ability = LookAtTopOfLibrary() }
        }
        registry.register(lens)
        val lensId = id("composite-lens")
        val cards = (0..2).map { id("composite-$it") }
        val hidden = id("composite-hidden")
        val extra = id("composite-extra")
        var initial = game(cards, listOf(extra))
        initial = initial.withEntity(lensId, CardEntityFactory.create(lens, p1))
            .addToZone(ZoneKey(p1, Zone.BATTLEFIELD), lensId)
            .withEntity(hidden, cardEntity(hidden.value))
            .addToZone(ZoneKey(p1, Zone.HAND), hidden)
        val effect = CompositeEffect(listOf(
            Effects.DrawCards(1),
            MoveCollectionEffect(
                from = "putBack",
                destination = CardDestination.ToZone(Zone.LIBRARY, placement = ZonePlacement.Top),
            ),
        ))
        val context = EffectContext(
            sourceId = null, controllerId = p1,
            pipeline = PipelineState(storedCollections = mapOf("putBack" to listOf(hidden))),
        )
        val resolved = EngineServices(registry).effectExecutorRegistry.execute(initial, effect, context)
        resolved.error shouldBe null
        resolved.pendingDecision shouldBe null
        resolved.state.getZone(libraryZone) shouldBe listOf(hidden, cards[1], cards[2])
        anchor(resolved.state, cards[1]) shouldBe LibraryPositionAnchor.TOP
        val withoutLens = resolved.state.removeFromZone(ZoneKey(p1, Zone.BATTLEFIELD), lensId)
        val prepared = preparation.prepare(withoutLens, p1, listOf(extra))
        prepared.error shouldBe null
        prepared.state.getZone(libraryZone)[1] shouldBe cards[1]
        visibility.isCardIdentityVisibleTo(prepared.state, libraryZone, cards[1], p1) shouldBe true
        visibility.isCardIdentityVisibleTo(prepared.state, libraryZone, cards[1], p2) shouldBe false
    }

    test("invalid selections and unavailable game states are rejected without changes") {
        val cards = listOf(id("library"))
        val reserved = id("reserved")
        val initial = game(cards, listOf(reserved))
        val token = initial.updateEntity(reserved) { it.with(TokenComponent) }
        val otherOwner = initial.updateEntity(reserved) {
            it.with(OwnerComponent(p2)).with(requireNotNull(it.get<CardComponent>()).copy(ownerId = p2))
        }
        val placed = initial.addToZone(ZoneKey(p1, Zone.HAND), reserved)
        val invalid = listOf(
            initial to listOf(reserved, reserved),
            initial to listOf(cards.first()),
            initial to listOf(id("missing")),
            token to listOf(reserved),
            otherOwner to listOf(reserved),
            placed to listOf(reserved),
            initial.copy(gameOver = true) to listOf(reserved),
            initial.copy(activePlayerId = p2) to listOf(reserved),
            initial.copy(stack = listOf(id("spell"))) to listOf(reserved),
            initial.copy(otaria = null) to listOf(reserved),
        )
        for ((before, choice) in invalid) {
            val result = preparation.prepare(before, p1, choice)
            result.error shouldNotBe null
            result.state shouldBe before
            result.events shouldBe emptyList()
        }
    }

    test("conflicting anchors preserve known order and favor top positions") {
        val cards = (0..7).map { id("conflict-$it") }
        val extras = listOf(id("extra-1"), id("extra-2"))
        var initial = game(cards, extras)
        initial = reveal(initial, cards[1], anchor = LibraryPositionAnchor.BOTTOM)
        initial = reveal(initial, cards[2], anchor = LibraryPositionAnchor.BOTTOM)
        initial = reveal(initial, cards[4], anchor = LibraryPositionAnchor.TOP)
        initial = reveal(initial, cards[5], anchor = LibraryPositionAnchor.BOTTOM)
        initial = reveal(initial, cards[6], anchor = LibraryPositionAnchor.TOP)
        initial = reveal(initial, cards[7], anchor = LibraryPositionAnchor.BOTTOM)
        val result = preparation.prepare(initial, p1, extras)
        result.error shouldBe null
        val after = result.state.getZone(libraryZone)
        listOf(2, 3, 4, 5, 6, 9).map(after::get) shouldBe
            listOf(cards[1], cards[2], cards[4], cards[5], cards[6], cards[7])
        after.toSet() shouldBe (cards + extras).toSet()
        for (card in listOf(cards[1], cards[2], cards[4], cards[5], cards[6], cards[7])) {
            visibility.isCardIdentityVisibleTo(result.state, libraryZone, card, p1) shouldBe true
        }
    }

    test("top priority preserves all feasible bottom positions across anchor combinations") {
        val options = listOf(
            null, LibraryPositionAnchor.TOP, LibraryPositionAnchor.BOTTOM, LibraryPositionAnchor.BOTH,
        )
        for (size in 1..5) {
            val combinations = (1..size).fold(1) { count, _ -> count * options.size }
            for (configuration in 0 until combinations) {
                var encoded = configuration
                val anchors = List(size) {
                    options[encoded % options.size].also { encoded /= options.size }
                }
                if (anchors.all { it != null }) continue
                for (addedCount in listOf(0, 2)) {
                    val cards = List(size) { id("property-$it") }
                    val extras = List(addedCount) { id("property-extra-$it") }
                    var initial = game(cards, extras)
                    for (index in cards.indices) {
                        val anchor = anchors[index] ?: continue
                        initial = reveal(initial, cards[index], anchor = anchor)
                    }
                    val result = preparation.prepare(initial, p1, extras)
                    result.error shouldBe null
                    val after = result.state.getZone(libraryZone)
                    val known = cards.indices.filter { anchors[it] != null }
                    val knownCards = known.map(cards::get)
                    after.filter { it in knownCards } shouldBe knownCards
                    for (index in known) {
                        val nextTop = (index + 1 until size).firstOrNull {
                            anchors[it] == LibraryPositionAnchor.TOP || anchors[it] == LibraryPositionAnchor.BOTH
                        }
                        val upper = if (nextTop == null) size + addedCount - 1 else {
                            nextTop - 1 - (index + 1 until nextTop).count { anchors[it] != null }
                        }
                        val expected = when (anchors[index]) {
                            LibraryPositionAnchor.TOP, LibraryPositionAnchor.BOTH -> index
                            LibraryPositionAnchor.BOTTOM -> minOf(index + addedCount, upper)
                            null -> error("Expected a known card")
                        }
                        after.indexOf(cards[index]) shouldBe expected
                    }
                }
            }
        }
    }

    test("preparation shuffle does not match spell-or-ability shuffle triggers and survives serialization") {
        val cards = listOf(id("a"), id("b"))
        val extra = id("extra")
        val initial = reveal(game(cards, listOf(extra)), cards.first(), anchor = LibraryPositionAnchor.TOP)
        val prepared = preparation.prepare(initial, p1, listOf(extra))
        val matcher = TriggerMatcher(evaluator, evaluator.conditions)
        val pattern = EventPattern.ShuffleLibraryEvent()
        val ruleShuffle = prepared.events.filterIsInstance<LibraryShuffledEvent>().single()
        matcher.matchesTrigger(pattern, TriggerBinding.ANY, ruleShuffle, p2, p2, prepared.state) shouldBe false
        matcher.matchesTrigger(
            pattern, TriggerBinding.ANY, ruleShuffle.copy(cause = ShuffleCause.SPELL_OR_ABILITY),
            p2, p2, prepared.state,
        ) shouldBe true
        val json = Json { serializersModule = engineSerializersModule; allowStructuredMapKeys = true }
        json.decodeFromString<ExecutionResult>(json.encodeToString(prepared)) shouldBe prepared
    }

    test("ordinary games do not record Otaria position metadata or change the reveal format") {
        val cards = listOf(id("a"), id("b"))
        val ordinary = game(cards).copy(otaria = null)
        val after = reveal(ordinary, cards.first(), anchor = LibraryPositionAnchor.TOP)
        after.getEntity(cards.first())?.get<RevealedToComponent>()?.libraryPositionAnchors shouldBe emptyMap()
        val encoded = Json { encodeDefaults = true }.encodeToString(RevealedToComponent(setOf(p1)))
        encoded.contains("libraryPositionAnchors") shouldBe false
    }
})
