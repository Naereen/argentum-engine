package com.wingedsheep.gameserver.session

import com.wingedsheep.engine.core.ActionProcessor
import com.wingedsheep.engine.core.ClaimOtariaMirariVictory
import com.wingedsheep.engine.core.EngineServices
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.engine.handlers.PredicateEvaluator
import com.wingedsheep.engine.otaria.OtariaExplorationPile
import com.wingedsheep.engine.otaria.OtariaLocation
import com.wingedsheep.engine.otaria.OtariaPlayerState
import com.wingedsheep.engine.otaria.OtariaState
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.identity.FaceDownComponent
import com.wingedsheep.engine.state.components.identity.OwnerComponent
import com.wingedsheep.engine.state.components.identity.PlayerComponent
import com.wingedsheep.engine.view.ClientStateTransformer
import com.wingedsheep.gameserver.protocol.GameOverReason
import com.wingedsheep.gameserver.protocol.ServerMessage
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.springframework.web.socket.WebSocketSession

class OtariaSeatIdentityTest : FunSpec({
    val p1 = EntityId("player-1")
    val p2 = EntityId("player-2")
    val p3 = EntityId("player-3")
    val players = listOf(p1, p2, p3)
    val card = EntityId("card-mirari")
    val registry = CardRegistry()
    val json = Json { encodeDefaults = true; serializersModule = engineSerializersModule }

    fun initial(): GameState {
        val location = OtariaLocation.KROSIAN_FOREST
        val mode = OtariaState(
            players = players.associateWith {
                OtariaPlayerState(
                    startingLocation = location,
                    position = location.position,
                    locationCards = if (it == p1) listOf(location) else emptyList(),
                )
            },
            explorationsUsed = OtariaLocation.entries.associateWith { 0 },
            starPile = OtariaExplorationPile(emptyList()),
            colorPiles = Color.entries.associateWith { OtariaExplorationPile(emptyList()) },
            treasurePile = emptyList(),
            locationPile = emptyList(),
        )
        val mirari = CardComponent(
            cardDefinitionId = "Mirari",
            name = "Mirari",
            manaCost = ManaCost.parse("{3}"),
            typeLine = TypeLine(cardTypes = setOf(CardType.ARTIFACT)),
            ownerId = p1,
        )
        return GameState(
            entities = players.associateWith { ComponentContainer.of(PlayerComponent(it.value)) },
            turnOrder = players,
            activePlayerId = p1,
            priorityPlayerId = p1,
            phase = Phase.PRECOMBAT_MAIN,
            step = Step.PRECOMBAT_MAIN,
            otaria = mode,
        ).withEntity(card, ComponentContainer.of(mirari, OwnerComponent(p1), ControllerComponent(p2)))
            .addToZone(ZoneKey(p2, Zone.BATTLEFIELD), card)
    }

    fun toReserve(state: GameState, holder: EntityId): GameState {
        val mode = requireNotNull(state.otaria)
        val oldHolder = mode.players.entries.firstOrNull { card in it.value.reserve }?.key
        val players = mode.players.mapValues { (id, player) ->
            when (id) {
                holder -> player.copy(reserve = player.reserve + card)
                oldHolder -> player.copy(reserve = player.reserve - card)
                else -> player
            }
        }
        return state.removeFromZone(ZoneKey(p2, Zone.BATTLEFIELD), card)
            .copy(otaria = mode.copy(players = players))
    }

    fun toFaceDownBattlefield(state: GameState): GameState {
        val mode = requireNotNull(state.otaria)
        val player = mode.players.getValue(p2)
        return state.copy(otaria = mode.copy(players = mode.players + (p2 to player.copy(reserve = emptyList()))))
            .updateEntity(card) { it.with(FaceDownComponent) }
            .addToZone(ZoneKey(p2, Zone.BATTLEFIELD), card)
    }

    fun sessions(): Map<EntityId, PlayerSession> = players.associateWith { player ->
        val socket = mockk<WebSocketSession>(relaxed = true) { every { id } returns player.value }
        PlayerSession(socket, player, player.value)
    }

    fun full(session: GameSession, state: GameState, viewer: EntityId): ServerMessage.StateUpdate {
        session.injectStateForTesting(state, sessions())
        session.clearLastSentState(viewer)
        return session.createStateUpdate(viewer, emptyList()) as ServerMessage.StateUpdate
    }

    test("a renamed card in a private reserve and in its Mirari action uses the same seat identity") {
        val session = GameSession(cardRegistry = registry)
        val first = initial()
        full(session, first, p1).state.cards.containsKey(card) shouldBe true
        val hidden = toReserve(first, p2)
        val hiddenUpdate = full(session, hidden, p1)
        json.encodeToString(ServerMessage.serializer(), hiddenUpdate) shouldNotContain card.value

        val owned = toReserve(hidden, p1)
        val update = full(session, owned, p1)
        val reserveId = requireNotNull(update.state.otaria)
            .players.single { it.playerId == p1 }.privateData!!.reserve.single().id
        reserveId shouldNotBe card
        val offered = update.legalActions.mapNotNull { it.action as? ClaimOtariaMirariVictory }.single()
        offered.mirariId shouldBe reserveId
        session.fromSeat(p1, listOf(reserveId), ListSerializer(EntityId.serializer())) shouldBe listOf(card)
        session.fromSeat(p1, listOf(card), ListSerializer(EntityId.serializer())) shouldBe null
    }

    test("spectators cannot follow a card through a reserve, even after persistence") {
        val builder = SpectatorStateBuilder(registry, ClientStateTransformer(registry, predicateEvaluator = PredicateEvaluator(registry)))
        val seats = players.map { SpectatorSeat(it, it.value) }
        val first = initial()
        val before = builder.buildState(first, seats, emptyList(), "session")
        requireNotNull(before.gameState).cards.containsKey(card) shouldBe true
        val hidden = toReserve(first, p2)
        val hiddenUpdate = builder.buildState(hidden, seats, emptyList(), "session")
        json.encodeToString(ServerMessage.serializer(), hiddenUpdate) shouldNotContain card.value

        val restored = SpectatorStateBuilder(registry, ClientStateTransformer(registry, predicateEvaluator = PredicateEvaluator(registry)))
        restored.restoreNames(builder.namesForPersistence())
        val faceDown = toFaceDownBattlefield(hidden)
        val update = restored.buildState(faceDown, seats, emptyList(), "session")
        val maskedId = requireNotNull(update.gameState)
            .zones.single { it.zoneId == ZoneKey(p2, Zone.BATTLEFIELD) }.cardIds.single()
        maskedId shouldNotBe card
        update.player2.battlefield.single().entityId shouldBe maskedId.value
        json.encodeToString(ServerMessage.serializer(), update) shouldNotContain card.value

        val next = restored.buildState(faceDown, seats, emptyList(), "session")
        requireNotNull(next.gameState)
            .zones.single { it.zoneId == ZoneKey(p2, Zone.BATTLEFIELD) }.cardIds.single() shouldBe maskedId
    }

    test("a Mirari victory retains its distinct game-over reason after state serialization") {
        val before = toReserve(initial(), p1)
        val result = ActionProcessor(EngineServices(registry)).process(before, ClaimOtariaMirariVictory(p1, card)).result
        result.error shouldBe null
        before.otaria?.mirariVictoryClaimed shouldBe false
        result.state.otaria?.mirariVictoryClaimed shouldBe true

        val stateJson = Json { serializersModule = engineSerializersModule; allowStructuredMapKeys = true }
        val restored = stateJson.decodeFromString<GameState>(stateJson.encodeToString(GameState.serializer(), result.state))
        val session = GameSession(cardRegistry = registry)
        session.injectStateForDevScenario(restored)
        session.getGameOverReason() shouldBe GameOverReason.OTARIA_MIRARI

        session.injectStateForDevScenario(before.copy(gameOver = true, winnerId = p1))
        session.getGameOverReason() shouldBe GameOverReason.LIFE_ZERO
    }
})
