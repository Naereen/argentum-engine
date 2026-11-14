package com.wingedsheep.engine.otaria

import com.wingedsheep.engine.core.CardsRevealedEvent
import com.wingedsheep.engine.core.ExecutionResult
import com.wingedsheep.engine.core.GameEndReason
import com.wingedsheep.engine.core.GameEndedEvent
import com.wingedsheep.engine.core.OtariaCardAddedToReserveEvent
import com.wingedsheep.engine.core.OtariaDiscoveredTreasure
import com.wingedsheep.engine.core.OtariaDiscoveryFinishedEvent
import com.wingedsheep.engine.core.OtariaDiscoveryTreasureResolvedEvent
import com.wingedsheep.engine.core.OtariaExplorationEmptyEvent
import com.wingedsheep.engine.core.OtariaExplorationPileShuffledEvent
import com.wingedsheep.engine.core.OtariaExplorationStartedEvent
import com.wingedsheep.engine.core.OtariaPlayerMovedEvent
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.engine.core.revealsInformation
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.PredicateEvaluator
import com.wingedsheep.engine.mechanics.layers.Layer
import com.wingedsheep.engine.mechanics.layers.SerializableModification
import com.wingedsheep.engine.mechanics.layers.addFloatingEffect
import com.wingedsheep.engine.mechanics.sba.player.playerCantLoseGame
import com.wingedsheep.engine.mechanics.sba.player.playerCantWinGame
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.GrantsCantLoseGameComponent
import com.wingedsheep.engine.state.components.battlefield.GrantsOpponentsCantWinGameComponent
import com.wingedsheep.engine.state.components.battlefield.PhasedOutComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.identity.FaceDownComponent
import com.wingedsheep.engine.state.components.identity.OwnerComponent
import com.wingedsheep.engine.state.components.identity.PlayerComponent
import com.wingedsheep.engine.state.components.identity.TokenComponent
import com.wingedsheep.engine.state.components.player.LossReason
import com.wingedsheep.engine.state.components.player.PlayerLostComponent
import com.wingedsheep.engine.view.ClientEventTransformer
import com.wingedsheep.engine.view.ClientStateTransformer
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.scripting.Duration
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class OtariaRulesTest : FunSpec({
    val players = (1..5).map { EntityId("player-$it") }
    val p1 = players[0]
    val p2 = players[1]
    val p3 = players[2]
    val mirari = EntityId("mirari")

    fun game(count: Int = 3): GameState {
        val seats = players.take(count)
        val locations = OtariaLocation.entries.take(count)
        val mode = OtariaState(
            players = seats.zip(locations).associate { (player, start) ->
                player to OtariaPlayerState(start, start.position)
            },
            explorationsUsed = OtariaLocation.entries.associateWith { 0 },
            starPile = OtariaExplorationPile(listOf(
                OtariaPileCard("Boss", OtariaCardKind.BOSS),
                OtariaPileCard("Discovery", OtariaCardKind.DISCOVERY),
            )),
            colorPiles = Color.entries.associateWith {
                OtariaExplorationPile(listOf(OtariaPileCard("$it spell", OtariaCardKind.NONCREATURE)))
            },
            treasurePile = emptyList(),
            locationPile = emptyList(),
        )
        return GameState(
            entities = seats.associateWith { ComponentContainer.of(PlayerComponent(it.value)) },
            turnOrder = seats,
            activePlayerId = p1,
            priorityPlayerId = p1,
            otaria = mode,
        )
    }

    fun move(state: GameState, playerId: EntityId, location: OtariaLocation, visited: Boolean = false): GameState {
        val mode = requireNotNull(state.otaria)
        val player = mode.players.getValue(playerId)
        return state.copy(otaria = mode.copy(players = mode.players + (
            playerId to player.copy(position = location.position, hasExploredLocationAwayFromStart = visited)
        )))
    }

    fun discoveryGame(treasures: List<String>, locations: List<OtariaLocation>): GameState {
        val state = move(game(), p1, OtariaLocation.ORDER_ENCAMPMENT)
        val mode = requireNotNull(state.otaria)
        return state.copy(otaria = mode.copy(
            starPile = OtariaExplorationPile(listOf(OtariaPileCard(OtariaSetup.DISCOVERY, OtariaCardKind.DISCOVERY))),
            treasurePile = treasures.map { OtariaTreasure.Card(it) },
            locationPile = locations,
        ))
    }

    fun card(name: String, owner: EntityId): CardComponent = CardComponent(
        cardDefinitionId = name,
        name = name,
        manaCost = ManaCost(emptyList()),
        typeLine = TypeLine(cardTypes = setOf(CardType.ARTIFACT)),
        ownerId = owner,
    )

    fun withReserveMirari(state: GameState, holder: EntityId, owner: EntityId = holder): GameState {
        val mode = requireNotNull(state.otaria)
        val player = mode.players.getValue(holder)
        return state.withEntity(mirari, ComponentContainer.of(card("Mirari", owner), OwnerComponent(owner)))
            .copy(otaria = mode.copy(players = mode.players + (holder to player.copy(reserve = listOf(mirari)))))
    }

    fun withLocationCard(state: GameState, playerId: EntityId, location: OtariaLocation): GameState {
        val positioned = move(state, playerId, location)
        val mode = requireNotNull(positioned.otaria)
        val player = mode.players.getValue(playerId)
        return positioned.copy(otaria = mode.copy(players = mode.players + (
            playerId to player.copy(locationCards = listOf(location))
        )))
    }

    fun withBattlefieldCard(state: GameState, owner: EntityId, controller: EntityId): GameState =
        state.withEntity(mirari, ComponentContainer.of(
            card("Mirari", owner), OwnerComponent(owner), ControllerComponent(controller),
        )).addToZone(ZoneKey(owner, Zone.BATTLEFIELD), mirari)

    val encounterRegistry = CardRegistry().also { registry ->
        listOf("Boss", "Boss 1", "Boss 2").forEach { name ->
            registry.register(CardDefinition.creature(name, ManaCost.ZERO, setOf(Subtype("Wizard")), 1, 1))
        }
    }

    fun startLocation(state: GameState, playerId: EntityId): ExecutionResult =
        OtariaRules.startLocationExploration(state, playerId, takeLand = false, cardRegistry = encounterRegistry)

    test("a missing encounter definition is drawn for the unscripted fallback; a non-creature one rejects the exploration") {
        val initial = move(game(), p1, OtariaLocation.ORDER_ENCAMPMENT)
        val registry = CardRegistry().also { it.register(CardDefinition.basicLand("Plains", Subtype.PLAINS)) }
        val missing = OtariaRules.startLocationExploration(initial, p1, takeLand = true, cardRegistry = registry)
        missing.error shouldBe null
        requireNotNull(missing.state.otaria).pendingExploration?.card?.name shouldBe "Boss"

        registry.register(CardDefinition.instant("Boss", ManaCost.ZERO, ""))
        val wrongType = OtariaRules.startLocationExploration(initial, p1, takeLand = true, cardRegistry = registry)
        wrongType.error shouldNotBe null
        wrongType.state shouldBe initial
        wrongType.events shouldBe emptyList()
    }

    test("unopposed movement is one or two spaces and leaves other board state unchanged") {
        val initial = game()
        val start = requireNotNull(initial.otaria).players.getValue(p1).position
        val one = OtariaBoard.neighbours(start).first()
        val two = OtariaBoard.tiles.keys.first { OtariaBoard.distance(start, it) == 2L }
        val three = OtariaBoard.tiles.keys.first { OtariaBoard.distance(start, it) == 3L }
        val moved = OtariaRules.moveUnopposed(initial, p1, two)
        moved.error shouldBe null
        val after = requireNotNull(moved.state.otaria)
        after.players.getValue(p1).position shouldBe two
        after.explorationsUsed shouldBe requireNotNull(initial.otaria).explorationsUsed
        after.starPile shouldBe requireNotNull(initial.otaria).starPile
        moved.events.single() shouldBe OtariaPlayerMovedEvent(p1, start, two)
        moved.events.single().revealsInformation shouldBe true
        ClientEventTransformer.transform(moved.events, p2, moved.state) shouldBe emptyList()
        val json = Json { serializersModule = engineSerializersModule; allowStructuredMapKeys = true }
        json.decodeFromString<ExecutionResult>(json.encodeToString(moved)) shouldBe moved
        requireNotNull(initial.otaria).players.getValue(p1).position shouldBe start
        OtariaRules.moveUnopposed(initial, p1, one).error shouldBe null
        for (destination in listOf(start, three, OtariaPosition(-1, 0))) {
            val denied = OtariaRules.moveUnopposed(initial, p1, destination)
            denied.error shouldNotBe null
            denied.state shouldBe initial
            denied.events shouldBe emptyList()
        }
        val wrongPlayer = OtariaRules.moveUnopposed(initial, p2, one)
        wrongPlayer.error shouldNotBe null
        wrongPlayer.state shouldBe initial
        val noMode = initial.copy(otaria = null)
        OtariaRules.moveUnopposed(noMode, p1, one).error shouldNotBe null
        val ended = initial.copy(gameOver = true)
        OtariaRules.moveUnopposed(ended, p1, one).error shouldNotBe null
        val pending = OtariaRules.startTerrainExploration(initial, p1, takeLand = false, cardRegistry = CardRegistry())
        val blocked = OtariaRules.moveUnopposed(pending.state, p1, one)
        blocked.error shouldNotBe null
        blocked.state shouldBe pending.state
    }

    test("movement counts projected control and excludes phased-out permanents at the ten-permanent threshold") {
        val base = game()
        val start = requireNotNull(base.otaria).players.getValue(p1).position
        val one = OtariaBoard.neighbours(start).first()
        val two = OtariaBoard.tiles.keys.first { OtariaBoard.distance(start, it) == 2L }
        fun addPermanent(state: GameState, id: EntityId, owner: EntityId, controller: EntityId): GameState =
            state.withEntity(id, ComponentContainer.of(
                card("Permanent", owner), OwnerComponent(owner), ControllerComponent(controller),
            )).addToZone(ZoneKey(owner, Zone.BATTLEFIELD), id)

        var nine = base
        for (index in 1..9) nine = addPermanent(nine, EntityId("permanent-$index"), p1, p1)
        OtariaRules.moveUnopposed(nine, p1, two).error shouldBe null
        val tenth = EntityId("permanent-10")
        val ten = addPermanent(nine, tenth, p1, p1)
        val denied = OtariaRules.moveUnopposed(ten, p1, two)
        denied.error shouldNotBe null
        denied.state shouldBe ten
        OtariaRules.moveUnopposed(ten, p1, one).error shouldBe null

        val phasedOut = ten.updateEntity(tenth) { it.with(PhasedOutComponent(p1)) }
        OtariaRules.moveUnopposed(phasedOut, p1, two).error shouldBe null
        val opponentPermanent = addPermanent(nine, tenth, p2, p2)
        OtariaRules.moveUnopposed(opponentPermanent, p1, two).error shouldBe null
        val stolen = opponentPermanent.addFloatingEffect(
            layer = Layer.CONTROL,
            modification = SerializableModification.ChangeController(p1),
            affectedEntities = setOf(tenth),
            duration = Duration.EndOfTurn,
            context = EffectContext(tenth, p1),
        )
        stolen.getEntity(tenth)?.get<ControllerComponent>()?.playerId shouldBe p2
        OtariaRules.moveUnopposed(stolen, p1, two).error shouldNotBe null
        OtariaRules.moveUnopposed(stolen, p1, one).error shouldBe null
    }

    test("the first special exploration cannot be at the player's own start") {
        val initial = game()
        val denied = startLocation(initial, p1)
        denied.error shouldNotBe null
        denied.state shouldBe initial
        denied.events shouldBe emptyList()

        val away = move(initial, p1, OtariaLocation.ORDER_ENCAMPMENT)
        val result = startLocation(away, p1)
        result.error shouldBe null
        val mode = requireNotNull(result.state.otaria)
        mode.players.getValue(p1).hasExploredLocationAwayFromStart shouldBe true
        mode.explorationsUsed.getValue(OtariaLocation.ORDER_ENCAMPMENT) shouldBe 1
        mode.starPile.cards.size shouldBe 1
        mode.pendingExploration shouldBe OtariaPendingExploration(
            p1, p2, OtariaExplorationSource.Star(OtariaLocation.ORDER_ENCAMPMENT),
            OtariaPileCard("Boss", OtariaCardKind.BOSS),
        )
        result.events.single() shouldBe OtariaExplorationStartedEvent(
            p1, p2, OtariaExplorationSource.Star(OtariaLocation.ORDER_ENCAMPMENT),
            OtariaPileCard("Boss", OtariaCardKind.BOSS),
        )
        result.events.single().revealsInformation shouldBe true
        ClientEventTransformer.transform(result.events, p1, result.state) shouldBe emptyList()
        ClientEventTransformer.transform(result.events, p2, result.state) shouldBe emptyList()
        val json = Json { serializersModule = engineSerializersModule; allowStructuredMapKeys = true }
        json.decodeFromString<ExecutionResult>(json.encodeToString(result)) shouldBe result
        startLocation(result.state, p1).error shouldNotBe null

        val resolved = result.state.copy(otaria = mode.copy(pendingExploration = null))
        val returnedToStart = move(resolved, p1, OtariaLocation.KROSIAN_FOREST, visited = true)
        startLocation(returnedToStart, p1).error shouldBe null
    }

    test("invalid exploration requests are rejected without changing state") {
        val positioned = move(game(), p1, OtariaLocation.ORDER_ENCAMPMENT)
        val mode = requireNotNull(positioned.otaria)
        val player = mode.players.getValue(p1)
        val noOpponent = positioned
            .updateEntity(p2) { it.with(PlayerLostComponent(LossReason.CONCESSION)) }
            .updateEntity(p3) { it.with(PlayerLostComponent(LossReason.CONCESSION)) }
        val invalidStates = listOf(
            positioned.copy(otaria = null),
            positioned.copy(gameOver = true),
            positioned.copy(activePlayerId = p2),
            positioned.copy(otaria = mode.copy(players = mode.players + (
                p1 to player.copy(position = OtariaPosition(0, 0))
            ))),
            positioned.copy(otaria = mode.copy(explorationsUsed = mode.explorationsUsed - OtariaLocation.ORDER_ENCAMPMENT)),
            positioned.copy(otaria = mode.copy(
                explorationsUsed = mode.explorationsUsed + (OtariaLocation.ORDER_ENCAMPMENT to -1),
            )),
            noOpponent,
        )
        for (invalid in invalidStates) {
            val result = startLocation(invalid, p1)
            result.error shouldNotBe null
            result.state shouldBe invalid
            result.events shouldBe emptyList()
        }
    }

    test("location limits are consumed at draw time and enforced for each player count") {
        for (count in 3..5) {
            for (location in OtariaLocation.entries) {
                val positioned = move(game(count), p1, location, visited = true)
                val mode = requireNotNull(positioned.otaria)
                val limit = location.explorationLimit(count)
                val oneLeft = positioned.copy(otaria = mode.copy(
                    explorationsUsed = mode.explorationsUsed + (location to limit - 1),
                ))
                val result = startLocation(oneLeft, p1)
                result.error shouldBe null
                val after = requireNotNull(result.state.otaria)
                after.explorationsUsed.getValue(location) shouldBe limit
                val encounterFinished = result.state.copy(otaria = after.copy(pendingExploration = null))
                val denied = startLocation(encounterFinished, p1)
                denied.error shouldNotBe null
                denied.state shouldBe encounterFinished
            }
        }
    }

    test("an empty star pile reshuffles its discard deterministically before drawing") {
        val positioned = move(game(), p1, OtariaLocation.ORDER_ENCAMPMENT)
        val mode = requireNotNull(positioned.otaria)
        val discarded = listOf(
            OtariaPileCard("Boss 1", OtariaCardKind.BOSS),
            OtariaPileCard("Boss 2", OtariaCardKind.BOSS),
            OtariaPileCard(OtariaSetup.DISCOVERY, OtariaCardKind.DISCOVERY),
        )
        val initial = positioned.copy(otaria = mode.copy(starPile = OtariaExplorationPile(emptyList(), discarded)))
        val result = startLocation(initial, p1)
        result.error shouldBe null
        startLocation(initial, p1) shouldBe result
        result.state.rng shouldNotBe initial.rng
        val after = requireNotNull(result.state.otaria)
        after.starPile.discard shouldBe emptyList()
        after.starPile.cards.size shouldBe 2
        (after.starPile.cards + requireNotNull(after.pendingExploration).card).toSet() shouldBe discarded.toSet()
        after.explorationsUsed.getValue(OtariaLocation.ORDER_ENCAMPMENT) shouldBe 1
        result.events.first() shouldBe OtariaExplorationPileShuffledEvent(
            OtariaExplorationSource.Star(OtariaLocation.ORDER_ENCAMPMENT), 3,
        )
        result.events.all { it.revealsInformation } shouldBe true
        requireNotNull(initial.otaria).starPile.discard shouldBe discarded

        val limit = OtariaLocation.ORDER_ENCAMPMENT.explorationLimit(3)
        val exhausted = initial.copy(otaria = requireNotNull(initial.otaria).copy(
            explorationsUsed = mode.explorationsUsed + (OtariaLocation.ORDER_ENCAMPMENT to limit),
        ))
        val denied = startLocation(exhausted, p1)
        denied.error shouldNotBe null
        denied.state shouldBe exhausted
        denied.events shouldBe emptyList()
    }

    test("a discovery gives a private treasure before the optional location and can lead to Mirari victory") {
        val target = OtariaLocation.ORDER_ENCAMPMENT
        val initial = discoveryGame(listOf(OtariaSetup.MIRARI), listOf(target, OtariaLocation.KROSIAN_FOREST))
        val started = startLocation(initial, p1)
        val registry = CardRegistry().also {
            it.register(CardDefinition.artifact(OtariaSetup.MIRARI, ManaCost.parse("{3}")))
        }
        val tooEarly = OtariaRules.finishDiscovery(started.state, p1, takeLocation = true)
        tooEarly.error shouldNotBe null
        tooEarly.state shouldBe started.state

        val treasure = OtariaRules.resolveDiscoveryTreasure(started.state, p1, registry)
        treasure.error shouldBe null
        val during = requireNotNull(treasure.state.otaria)
        val treasureId = during.players.getValue(p1).reserve.single()
        treasure.state.getEntity(treasureId)?.get<CardComponent>()?.name shouldBe OtariaSetup.MIRARI
        treasure.state.getEntity(treasureId)?.get<OwnerComponent>()?.playerId shouldBe p1
        during.treasurePile shouldBe emptyList()
        during.locationPile shouldBe listOf(target, OtariaLocation.KROSIAN_FOREST)
        during.pendingExploration?.stage shouldBe OtariaExplorationStage.CHOOSING_LOCATION
        during.starPile.discard shouldBe emptyList()
        val drawn = treasure.events.single() as OtariaDiscoveryTreasureResolvedEvent
        drawn.treasure shouldBe OtariaDiscoveredTreasure.Card(treasureId, OtariaSetup.MIRARI)
        drawn.revealsInformation shouldBe true
        OtariaRules.resolveDiscoveryTreasure(treasure.state, p1, registry).error shouldNotBe null

        val transformer = ClientStateTransformer(registry, predicateEvaluator = PredicateEvaluator(registry))
        val json = Json { serializersModule = engineSerializersModule; allowStructuredMapKeys = true; encodeDefaults = true }
        json.decodeFromString<ExecutionResult>(json.encodeToString(treasure)) shouldBe treasure
        for ((viewer, spectator) in listOf(p1 to false, p2 to false, p3 to true)) {
            ClientEventTransformer.transform(treasure.events, viewer, treasure.state) shouldBe emptyList()
            val view = transformer.transform(treasure.state, viewer, isSpectator = spectator)
            val privateData = requireNotNull(view.otaria).players.single { it.playerId == p1 }.privateData
            if (viewer == p1 && !spectator) {
                privateData?.reserve?.single()?.name shouldBe "Mirari"
            } else {
                privateData shouldBe null
                json.encodeToString(view).contains("Mirari") shouldBe false
            }
        }

        val finished = OtariaRules.finishDiscovery(treasure.state, p1, takeLocation = true)
        finished.error shouldBe null
        val after = requireNotNull(finished.state.otaria)
        after.players.getValue(p1).locationCards shouldBe listOf(target)
        after.locationPile shouldBe listOf(OtariaLocation.KROSIAN_FOREST)
        after.starPile.discard shouldBe listOf(OtariaPileCard(OtariaSetup.DISCOVERY, OtariaCardKind.DISCOVERY))
        after.pendingExploration shouldBe null
        after.explorationsUsed.getValue(target) shouldBe 1
        finished.events.single() shouldBe OtariaDiscoveryFinishedEvent(p1, target)
        json.decodeFromString<ExecutionResult>(json.encodeToString(finished)) shouldBe finished
        for (viewer in listOf(p1, p2, p3)) {
            ClientEventTransformer.transform(finished.events, viewer, finished.state) shouldBe emptyList()
        }
        val victory = OtariaRules.claimMirariVictory(finished.state, p1, treasureId)
        victory.error shouldBe null
        victory.state.winnerId shouldBe p1
        requireNotNull(initial.otaria).treasurePile shouldBe listOf(OtariaTreasure.Card(OtariaSetup.MIRARI))
    }

    test("discovery allows an empty treasure pile and an optional or unavailable location") {
        val target = OtariaLocation.KROSIAN_FOREST
        val initial = discoveryGame(emptyList(), listOf(target))
        val started = startLocation(initial, p1)
        val treasure = OtariaRules.resolveDiscoveryTreasure(started.state, p1, CardRegistry())
        treasure.error shouldBe null
        (treasure.events.single() as OtariaDiscoveryTreasureResolvedEvent).treasure shouldBe null
        requireNotNull(treasure.state.otaria).players.getValue(p1).reserve shouldBe emptyList()

        val declined = OtariaRules.finishDiscovery(treasure.state, p1, takeLocation = false)
        declined.error shouldBe null
        requireNotNull(declined.state.otaria).locationPile shouldBe listOf(target)
        requireNotNull(declined.state.otaria).players.getValue(p1).locationCards shouldBe emptyList()
        val accepted = OtariaRules.finishDiscovery(treasure.state, p1, takeLocation = true)
        requireNotNull(accepted.state.otaria).players.getValue(p1).locationCards shouldBe listOf(target)

        val withoutLocations = treasure.state.copy(otaria = requireNotNull(treasure.state.otaria).copy(locationPile = emptyList()))
        val invalid = OtariaRules.finishDiscovery(withoutLocations, p1, takeLocation = true)
        invalid.error shouldNotBe null
        invalid.state shouldBe withoutLocations
        invalid.events shouldBe emptyList()
        OtariaRules.finishDiscovery(withoutLocations, p1, takeLocation = false).error shouldBe null
    }

    test("discovery puts scrap and rock tokens in a private reserve without creating cards") {
        val json = Json { serializersModule = engineSerializersModule; allowStructuredMapKeys = true }
        for (kind in OtariaTreasureToken.entries) {
            val base = discoveryGame(emptyList(), listOf(OtariaLocation.KROSIAN_FOREST))
            val before = requireNotNull(base.otaria)
            val player = before.players.getValue(p1)
            val initial = base.copy(otaria = before.copy(
                players = before.players + (p1 to player.copy(treasureTokens = mapOf(kind to 1))),
                treasurePile = listOf(OtariaTreasure.Token(kind)),
            ))
            val started = startLocation(initial, p1)
            val result = OtariaRules.resolveDiscoveryTreasure(started.state, p1, CardRegistry())
            result.error shouldBe null
            val after = requireNotNull(result.state.otaria)
            after.players.getValue(p1).treasureTokens shouldBe mapOf(kind to 2)
            after.players.getValue(p1).reserve shouldBe emptyList()
            after.players.getValue(p2).treasureTokens shouldBe emptyMap()
            after.treasurePile shouldBe emptyList()
            after.pendingExploration?.stage shouldBe OtariaExplorationStage.CHOOSING_LOCATION
            result.state.entities shouldBe started.state.entities
            result.state.rng shouldBe started.state.rng
            result.events.single() shouldBe OtariaDiscoveryTreasureResolvedEvent(p1, OtariaDiscoveredTreasure.Token(kind))
            json.decodeFromString<ExecutionResult>(json.encodeToString(result)) shouldBe result
            for (viewer in listOf(p1, p2, p3)) {
                ClientEventTransformer.transform(result.events, viewer, result.state) shouldBe emptyList()
            }
            val finished = OtariaRules.finishDiscovery(result.state, p1, takeLocation = true)
            finished.error shouldBe null
            requireNotNull(finished.state.otaria).players.getValue(p1).locationCards shouldBe
                listOf(OtariaLocation.KROSIAN_FOREST)
            requireNotNull(initial.otaria).treasurePile shouldBe listOf(OtariaTreasure.Token(kind))
        }
    }

    test("a discovery rejects missing card definitions and invalid actors without consuming state") {
        val initial = discoveryGame(listOf("Missing Treasure"), listOf(OtariaLocation.KROSIAN_FOREST))
        val started = startLocation(initial, p1)
        val unresolved = started.state
        val missing = OtariaRules.resolveDiscoveryTreasure(unresolved, p1, CardRegistry())
        missing.error shouldNotBe null
        missing.state shouldBe unresolved
        missing.events shouldBe emptyList()
        val wrongPlayer = OtariaRules.resolveDiscoveryTreasure(unresolved, p2, CardRegistry())
        wrongPlayer.error shouldNotBe null
        wrongPlayer.state shouldBe unresolved
        val boss = startLocation(move(game(), p1, OtariaLocation.ORDER_ENCAMPMENT), p1)
        OtariaRules.resolveDiscoveryTreasure(boss.state, p1, CardRegistry()).error shouldNotBe null
    }

    test("standard exploration on a starting star can take land without consuming a location exploration") {
        val initial = game()
        val registry = CardRegistry().also {
            it.register(CardDefinition.basicLand("Forest", Subtype.FOREST))
            it.register(CardDefinition.instant("GREEN spell", ManaCost.ZERO, ""))
        }
        val result = OtariaRules.startTerrainExploration(initial, p1, takeLand = true, cardRegistry = registry)
        result.error shouldBe null
        val before = requireNotNull(initial.otaria)
        val after = requireNotNull(result.state.otaria)
        val reserveId = after.players.getValue(p1).reserve.single()
        result.state.getEntity(reserveId)?.get<CardComponent>()?.name shouldBe "Forest"
        result.state.getEntity(reserveId)?.get<OwnerComponent>()?.playerId shouldBe p1
        after.players.getValue(p1).hasExploredLocationAwayFromStart shouldBe false
        after.explorationsUsed shouldBe before.explorationsUsed
        after.starPile shouldBe before.starPile
        after.colorPiles.getValue(Color.GREEN).cards shouldBe emptyList()
        after.pendingExploration?.source shouldBe OtariaExplorationSource.Terrain(Color.GREEN)
        after.pendingExploration?.managerId shouldBe p2
        result.events shouldBe listOf(
            OtariaCardAddedToReserveEvent(p1, reserveId, "Forest"),
            OtariaExplorationStartedEvent(
                p1, p2, OtariaExplorationSource.Terrain(Color.GREEN),
                OtariaPileCard("GREEN spell", OtariaCardKind.NONCREATURE),
            ),
        )
        result.events.all { it.revealsInformation } shouldBe true
        val json = Json { serializersModule = engineSerializersModule; allowStructuredMapKeys = true; encodeDefaults = true }
        json.decodeFromString<ExecutionResult>(json.encodeToString(result)) shouldBe result
        val transformer = ClientStateTransformer(registry, predicateEvaluator = PredicateEvaluator(registry))
        for ((viewer, spectator) in listOf(p1 to false, p2 to false, p3 to true)) {
            ClientEventTransformer.transform(result.events, viewer, result.state) shouldBe emptyList()
            val payload = json.encodeToString(transformer.transform(result.state, viewer, isSpectator = spectator))
            payload.contains("GREEN spell") shouldBe true
            payload.contains("Forest") shouldBe (viewer == p1 && !spectator)
        }
        startLocation(result.state, p1).error shouldNotBe null
        val resolved = OtariaRules.resolveNonCreature(result.state, p1, registry)
        resolved.error shouldBe null
        startLocation(resolved.state, p1).error shouldNotBe null
        requireNotNull(initial.otaria).players.getValue(p1).reserve shouldBe emptyList()
    }

    test("each terrain yields the matching optional basic land from an unlimited supply") {
        val landTypes = mapOf(
            Color.WHITE to Subtype.PLAINS,
            Color.BLUE to Subtype.ISLAND,
            Color.BLACK to Subtype.SWAMP,
            Color.RED to Subtype.MOUNTAIN,
            Color.GREEN to Subtype.FOREST,
        )
        val registry = CardRegistry().also { cards ->
            landTypes.values.forEach { cards.register(CardDefinition.basicLand(it.value, it)) }
            cards.register(CardDefinition.instant("Spell", ManaCost.ZERO, ""))
        }
        for ((color, subtype) in landTypes) {
            val tile = OtariaBoard.tiles.values.first { it.color == color && it.location == null }
            val base = game()
            val mode = requireNotNull(base.otaria)
            val player = mode.players.getValue(p1)
            val card = OtariaPileCard("Spell", OtariaCardKind.NONCREATURE)
            val initial = base.copy(otaria = mode.copy(
                players = mode.players + (p1 to player.copy(position = tile.position)),
                colorPiles = mode.colorPiles + (color to OtariaExplorationPile(listOf(card, card))),
            ))
            val withoutLand = OtariaRules.startTerrainExploration(
                initial, p1, takeLand = false, cardRegistry = CardRegistry(),
            )
            withoutLand.error shouldBe null
            requireNotNull(withoutLand.state.otaria).players.getValue(p1).reserve shouldBe emptyList()
            val first = OtariaRules.startTerrainExploration(initial, p1, takeLand = true, cardRegistry = registry)
            first.error shouldBe null
            val firstId = requireNotNull(first.state.otaria).players.getValue(p1).reserve.single()
            first.state.getEntity(firstId)?.get<CardComponent>()?.typeLine shouldBe TypeLine.basicLand(subtype)
            val resolved = OtariaRules.resolveNonCreature(first.state, p1, registry)
            resolved.error shouldBe null
            val second = OtariaRules.startTerrainExploration(resolved.state, p1, takeLand = true, cardRegistry = registry)
            second.error shouldBe null
            val reserve = requireNotNull(second.state.otaria).players.getValue(p1).reserve
            reserve.size shouldBe 3
            reserve.distinct().size shouldBe 3
            second.state.getEntity(reserve.last())?.get<CardComponent>()?.name shouldBe subtype.value
            requireNotNull(initial.otaria).players.getValue(p1).reserve shouldBe emptyList()
        }
    }

    test("star exploration replaces the color draw but still permits its basic land") {
        val initial = move(game(), p1, OtariaLocation.ORDER_ENCAMPMENT)
        val before = requireNotNull(initial.otaria)
        val registry = CardRegistry().also {
            it.register(CardDefinition.basicLand("Plains", Subtype.PLAINS))
            it.register(CardDefinition.creature("Boss", ManaCost.ZERO, setOf(Subtype("Wizard")), 1, 1))
        }
        val result = OtariaRules.startLocationExploration(initial, p1, takeLand = true, cardRegistry = registry)
        result.error shouldBe null
        val after = requireNotNull(result.state.otaria)
        val landId = after.players.getValue(p1).reserve.single()
        result.state.getEntity(landId)?.get<CardComponent>()?.name shouldBe "Plains"
        after.starPile.cards shouldBe before.starPile.cards.drop(1)
        after.colorPiles shouldBe before.colorPiles
        after.explorationsUsed.getValue(OtariaLocation.ORDER_ENCAMPMENT) shouldBe 1
        result.events.first() shouldBe OtariaCardAddedToReserveEvent(p1, landId, "Plains")
        (result.events.last() as OtariaExplorationStartedEvent).source shouldBe
            OtariaExplorationSource.Star(OtariaLocation.ORDER_ENCAMPMENT)
    }

    test("an exhausted exploration can take only the optional land without consuming a star quota") {
        val base = game()
        val mode = requireNotNull(base.otaria)
        val location = OtariaLocation.KROSIAN_FOREST
        val emptyStar = base.copy(otaria = mode.copy(
            starPile = OtariaExplorationPile(emptyList()),
            explorationsUsed = mode.explorationsUsed + (location to location.explorationLimit(3)),
        ))
        val emptyColor = base.copy(otaria = mode.copy(colorPiles = mode.colorPiles + (
            Color.GREEN to OtariaExplorationPile(emptyList())
        )))
        val registry = CardRegistry().also { it.register(CardDefinition.basicLand("Forest", Subtype.FOREST)) }
        fun explore(state: GameState, star: Boolean, takeLand: Boolean, cards: CardRegistry): ExecutionResult =
            if (star) OtariaRules.startLocationExploration(state, p1, takeLand, cards)
            else OtariaRules.startTerrainExploration(state, p1, takeLand, cards)
        val json = Json { serializersModule = engineSerializersModule; allowStructuredMapKeys = true }
        for ((initial, source) in listOf(
            emptyStar to OtariaExplorationSource.Star(location),
            emptyColor to OtariaExplorationSource.Terrain(Color.GREEN),
        )) {
            val star = source is OtariaExplorationSource.Star
            val before = requireNotNull(initial.otaria)
            val result = explore(initial, star, takeLand = true, cards = registry)
            result.error shouldBe null
            val after = requireNotNull(result.state.otaria)
            val landId = after.players.getValue(p1).reserve.single()
            result.state.getEntity(landId)?.get<CardComponent>()?.name shouldBe "Forest"
            after.explorationsUsed shouldBe before.explorationsUsed
            after.players.getValue(p1).hasExploredLocationAwayFromStart shouldBe false
            after.starPile shouldBe before.starPile
            after.colorPiles shouldBe before.colorPiles
            after.pendingExploration shouldBe null
            result.state.rng shouldBe initial.rng
            result.events shouldBe listOf(
                OtariaCardAddedToReserveEvent(p1, landId, "Forest"),
                OtariaExplorationEmptyEvent(p1, source),
            )
            result.events.all { it.revealsInformation } shouldBe true
            ClientEventTransformer.transform(result.events, p2, result.state) shouldBe emptyList()
            json.decodeFromString<ExecutionResult>(json.encodeToString(result)) shouldBe result
            val declined = explore(initial, star, takeLand = false, cards = CardRegistry())
            declined.error shouldBe null
            declined.state shouldBe initial
            declined.events.single() shouldBe OtariaExplorationEmptyEvent(p1, source)
            val missing = explore(initial, star, takeLand = true, cards = CardRegistry())
            missing.error shouldNotBe null
            missing.state shouldBe initial
            missing.events shouldBe emptyList()
        }
        val reshufflable = emptyStar.copy(otaria = requireNotNull(emptyStar.otaria).copy(
            starPile = OtariaExplorationPile(emptyList(), listOf(OtariaPileCard("Boss", OtariaCardKind.BOSS))),
        ))
        val denied = startLocation(reshufflable, p1)
        denied.error shouldNotBe null
        denied.state shouldBe reshufflable
    }

    test("missing piles and bad basic-land definitions fail atomically") {
        val base = game()
        val mode = requireNotNull(base.otaria)
        val missing = OtariaRules.startTerrainExploration(base, p1, takeLand = true, cardRegistry = CardRegistry())
        missing.error shouldNotBe null
        missing.state shouldBe base
        missing.events shouldBe emptyList()
        val wrongType = CardRegistry().also { it.register(CardDefinition.basicLand("Forest", Subtype.PLAINS)) }
        val invalid = OtariaRules.startTerrainExploration(base, p1, takeLand = true, cardRegistry = wrongType)
        invalid.error shouldNotBe null
        invalid.state shouldBe base
        invalid.events shouldBe emptyList()
        val absentColor = base.copy(otaria = mode.copy(colorPiles = mode.colorPiles - Color.GREEN))
        val absent = OtariaRules.startTerrainExploration(absentColor, p1, takeLand = false, cardRegistry = CardRegistry())
        absent.error shouldNotBe null
        absent.state shouldBe absentColor
    }

    test("a terrain discovery returns to its own discard and reshuffles deterministically") {
        val initial = game()
        val mode = requireNotNull(initial.otaria)
        val discovery = OtariaPileCard(OtariaSetup.DISCOVERY, OtariaCardKind.DISCOVERY)
        val discarded = listOf(discovery, discovery, discovery)
        val exhausted = initial.copy(otaria = mode.copy(colorPiles = mode.colorPiles + (
            Color.GREEN to OtariaExplorationPile(emptyList(), discarded)
        )))
        val start = OtariaRules.startTerrainExploration(exhausted, p1, takeLand = false, cardRegistry = CardRegistry())
        start.error shouldBe null
        OtariaRules.startTerrainExploration(exhausted, p1, takeLand = false, cardRegistry = CardRegistry()) shouldBe start
        start.state.rng shouldNotBe exhausted.rng
        val afterDraw = requireNotNull(start.state.otaria)
        afterDraw.colorPiles.getValue(Color.GREEN).cards shouldBe listOf(discovery, discovery)
        afterDraw.colorPiles.getValue(Color.GREEN).discard shouldBe emptyList()
        afterDraw.starPile shouldBe mode.starPile
        start.events.first() shouldBe OtariaExplorationPileShuffledEvent(OtariaExplorationSource.Terrain(Color.GREEN), 3)
        val treasure = OtariaRules.resolveDiscoveryTreasure(start.state, p1, CardRegistry())
        treasure.error shouldBe null
        val finished = OtariaRules.finishDiscovery(treasure.state, p1, takeLocation = false)
        finished.error shouldBe null
        val after = requireNotNull(finished.state.otaria)
        after.colorPiles.getValue(Color.GREEN).discard shouldBe listOf(discovery)
        after.starPile shouldBe mode.starPile
        after.explorationsUsed shouldBe mode.explorationsUsed
        after.pendingExploration shouldBe null
        requireNotNull(exhausted.otaria).colorPiles.getValue(Color.GREEN).discard shouldBe discarded
    }

    test("a noncreature draw is placed in reserve and invalid definitions do not consume it") {
        val initial = game()
        val mode = requireNotNull(initial.otaria)
        val card = OtariaPileCard("Spell", OtariaCardKind.NONCREATURE)
        val withCard = initial.copy(otaria = mode.copy(colorPiles = mode.colorPiles + (
            Color.GREEN to OtariaExplorationPile(listOf(card))
        )))
        val started = OtariaRules.startTerrainExploration(withCard, p1, takeLand = false, cardRegistry = CardRegistry())
        started.error shouldBe null
        val missing = OtariaRules.resolveNonCreature(started.state, p1, CardRegistry())
        missing.error shouldNotBe null
        missing.state shouldBe started.state
        val creature = CardRegistry().also {
            it.register(CardDefinition.creature("Spell", ManaCost.ZERO, emptySet(), 1, 1))
        }
        val invalid = OtariaRules.resolveNonCreature(started.state, p1, creature)
        invalid.error shouldNotBe null
        invalid.state shouldBe started.state
        OtariaRules.resolveNonCreature(started.state, p2, CardRegistry()).error shouldNotBe null
        val registry = CardRegistry().also { it.register(CardDefinition.instant("Spell", ManaCost.ZERO, "")) }
        val result = OtariaRules.resolveNonCreature(started.state, p1, registry)
        result.error shouldBe null
        val after = requireNotNull(result.state.otaria)
        val cardId = after.players.getValue(p1).reserve.single()
        result.state.getEntity(cardId)?.get<CardComponent>()?.name shouldBe "Spell"
        result.state.getEntity(cardId)?.get<OwnerComponent>()?.playerId shouldBe p1
        after.pendingExploration shouldBe null
        after.colorPiles.getValue(Color.GREEN).discard shouldBe emptyList()
        result.events.single() shouldBe OtariaCardAddedToReserveEvent(p1, cardId, "Spell")
        ClientEventTransformer.transform(result.events, p2, result.state) shouldBe emptyList()
        OtariaRules.resolveNonCreature(result.state, p1, registry).error shouldNotBe null
    }

    test("the next living seat on the left manages the draw, including across the end of turn order") {
        val lost = game().updateEntity(p2) { it.with(PlayerLostComponent(LossReason.CONCESSION)) }
        val away = move(lost, p1, OtariaLocation.ORDER_ENCAMPMENT)
        requireNotNull(startLocation(away, p1).state.otaria)
            .pendingExploration?.managerId shouldBe p3

        val lastSeat = move(game().copy(activePlayerId = p3), p3, OtariaLocation.ORDER_ENCAMPMENT)
        requireNotNull(startLocation(lastSeat, p3).state.otaria)
            .pendingExploration?.managerId shouldBe p1
    }

    test("an owned Mirari in reserve wins only at the matching secret location") {
        val matching = withLocationCard(withReserveMirari(game(), p1), p1, OtariaLocation.KROSIAN_FOREST)
        val result = OtariaRules.claimMirariVictory(matching, p1, mirari)
        result.error shouldBe null
        result.state.gameOver shouldBe true
        result.state.winnerId shouldBe p1
        (result.events[0] as CardsRevealedEvent).cardIds shouldBe listOf(mirari)
        result.events[1] shouldBe GameEndedEvent(p1, GameEndReason.ALTERNATIVE_WIN)
        matching.gameOver shouldBe false

        val wrongLocation = move(matching, p1, OtariaLocation.ORDER_ENCAMPMENT)
        OtariaRules.claimMirariVictory(wrongLocation, p1, mirari).error shouldNotBe null
        val notOwned = withLocationCard(withReserveMirari(game(), p1, p2), p1, OtariaLocation.KROSIAN_FOREST)
        OtariaRules.claimMirariVictory(notOwned, p1, mirari).error shouldNotBe null
        OtariaRules.claimMirariVictory(matching, p1, EntityId("missing")).error shouldNotBe null
    }

    test("private Otaria piles, location cards and reserves do not appear to other viewers") {
        val state = withLocationCard(withReserveMirari(game(), p1), p1, OtariaLocation.KROSIAN_FOREST)
        val registry = CardRegistry()
        val transformer = ClientStateTransformer(registry, predicateEvaluator = PredicateEvaluator(registry))
        val json = Json { allowStructuredMapKeys = true; encodeDefaults = true }
        for ((viewer, spectator) in listOf(p2 to false, p3 to true)) {
            val client = transformer.transform(state, viewer, isSpectator = spectator)
            client.cards.containsKey(mirari) shouldBe false
            val payload = json.encodeToString(client)
            payload.contains("Mirari") shouldBe false
            requireNotNull(client.otaria).players.single { it.playerId == p1 }.privateData shouldBe null
            payload.contains("Boss") shouldBe false
        }
    }

    test("battlefield Mirari uses projected control and name, and a face-down object is not Mirari") {
        val location = withLocationCard(game(), p1, OtariaLocation.KROSIAN_FOREST)
        val opponentControls = withBattlefieldCard(location, p2, p2)
        OtariaRules.claimMirariVictory(opponentControls, p1, mirari).error shouldNotBe null
        val stolen = opponentControls.addFloatingEffect(
            layer = Layer.CONTROL,
            modification = SerializableModification.ChangeController(p1),
            affectedEntities = setOf(mirari),
            duration = Duration.EndOfTurn,
            context = EffectContext(mirari, p1),
        )
        stolen.getEntity(mirari)?.get<ControllerComponent>()?.playerId shouldBe p2
        OtariaRules.claimMirariVictory(stolen, p1, mirari).error shouldBe null
        val renamed = stolen.addFloatingEffect(
            layer = Layer.TEXT,
            modification = SerializableModification.SetName("Not Mirari"),
            affectedEntities = setOf(mirari),
            duration = Duration.EndOfTurn,
            context = EffectContext(mirari, p1),
        )
        OtariaRules.claimMirariVictory(renamed, p1, mirari).error shouldNotBe null
        val faceDown = stolen.updateEntity(mirari) { it.with(FaceDownComponent) }
        OtariaRules.claimMirariVictory(faceDown, p1, mirari).error shouldNotBe null
        val token = stolen.updateEntity(mirari) { it.with(TokenComponent) }
        OtariaRules.claimMirariVictory(token, p1, mirari).error shouldNotBe null
    }

    test("the special victory is immediate despite effects that forbid winning or losing") {
        val base = withLocationCard(withReserveMirari(game(), p1), p1, OtariaLocation.KROSIAN_FOREST)
        val lock = EntityId("lock")
        val guarded = base.withEntity(lock, ComponentContainer.of(
            card("Lock", p2), OwnerComponent(p2), ControllerComponent(p2),
            GrantsCantLoseGameComponent(), GrantsOpponentsCantWinGameComponent(),
        )).addToZone(ZoneKey(p2, Zone.BATTLEFIELD), lock)
        val evaluator = PredicateEvaluator(cardRegistry = null)
        playerCantWinGame(guarded, p1, predicateEvaluator = evaluator) shouldBe true
        playerCantLoseGame(guarded, p2, predicateEvaluator = evaluator) shouldBe true
        val result = OtariaRules.claimMirariVictory(guarded, p1, mirari)
        result.error shouldBe null
        result.state.gameOver shouldBe true
        result.state.winnerId shouldBe p1
        result.state.stack shouldBe guarded.stack
    }
})
