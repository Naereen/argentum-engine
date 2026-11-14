package com.wingedsheep.gameserver.otaria

import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.LifeTotalComponent
import com.wingedsheep.gameserver.ai.AiGameManager
import com.wingedsheep.gameserver.ai.AiWebSocketSession
import com.wingedsheep.gameserver.repository.GameRepository
import com.wingedsheep.gameserver.session.SessionRegistry
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.engine.otaria.OtariaStartingLandSource
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.gameserver.jumpstart.JumpstartPacks
import io.kotest.assertions.throwables.shouldThrow
import kotlin.random.Random
import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.time.Duration.Companion.seconds

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "game.dev-endpoints.enabled=true",
        "game.ai.enabled=true",
        "game.ai.mode=engine",
    ],
)
class OtariaPlaytestTest(
    @param:Autowired private val cards: CardRegistry,
    @param:Autowired private val games: GameRepository,
    @param:Autowired private val sessions: SessionRegistry,
    @param:Autowired private val ai: AiGameManager,
    @param:Autowired private val playtest: OtariaPlaytestService,
    @param:LocalServerPort private val port: Int,
) : FunSpec({
    val http = HttpClient.newHttpClient()
    val json = Json { ignoreUnknownKeys = true }

    fun call(method: String, path: String, body: String = ""): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    fun post(body: String): HttpResponse<String> = call("POST", "/api/dev/otaria", body)

    test("all published J22 and J25 variants are offered unchanged with the correct categories") {
        val options = playtest.options()
        options.aiEnabled shouldBe true
        options.decks.size shouldBe 242
        options.decks.map { it.id }.distinct().size shouldBe 242
        options.decks.map { it.color }.toSet() shouldBe OtariaStartingColor.entries.toSet()
        options.limitations.isNotEmpty() shouldBe true
        for (deck in options.decks) {
            deck.cards.values.sum() shouldBe 20
            deck.cards shouldBe JumpstartPacks.listsFor(deck.setCode)
                .getValue(deck.id.substringAfter(":")).groupingBy { it }.eachCount()
            deck.cards.keys.forEach(cards::requireCard)
            deck.startingLands.size shouldBe 2
            deck.startingLands.all { cards.requireCard(it).typeLine.isBasicLand } shouldBe true
        }
        for (set in listOf("J22", "J25")) {
            options.decks.filter { it.setCode == set }.groupingBy { it.color }.eachCount() shouldBe
                OtariaStartingColor.entries.associateWith { if (it == OtariaStartingColor.OTHER) 1 else 24 }
        }
    }

    test("random selection is seeded, color constrained and covers every category including Other") {
        val packs = OtariaStartingPacks(cards)
        val first = Random(21)
        val second = Random(21)
        List(100) { packs.choose(null, null, first).id } shouldBe
            List(100) { packs.choose(null, null, second).id }
        val random = Random(42)
        List(300) { packs.choose(null, null, random).color }.toSet() shouldBe OtariaStartingColor.entries.toSet()
        for (color in OtariaStartingColor.entries) {
            val chosen = List(100) { packs.choose(color, null, random) }
            chosen.all { it.color == color } shouldBe true
            chosen.map { it.setCode }.toSet() shouldBe setOf("J22", "J25")
        }
        shouldThrow<IllegalArgumentException> { packs.choose(null, "unknown", random) }
        shouldThrow<IllegalArgumentException> { packs.choose(OtariaStartingColor.GREEN, "J22:Urza's", random) }
    }

    test("every exact booster initializes with its published card multiset and the declared starting lands") {
        for (deck in playtest.options().decks) {
            val result = playtest.initialize(OtariaPlaytestRequest(boosterId = deck.id, seed = 41))
            val id = result.playerIds.first()
            val battlefield = result.state.getZone(ZoneKey(id, Zone.BATTLEFIELD))
            battlefield.map { result.state.getEntity(it)?.get<CardComponent>()?.name } shouldBe deck.startingLands
            val all = listOf(Zone.HAND, Zone.LIBRARY, Zone.BATTLEFIELD)
                .flatMap { result.state.getZone(ZoneKey(id, it)) }
                .map { requireNotNull(result.state.getEntity(it)?.get<CardComponent>()).name }
                .groupingBy { it }.eachCount()
            val expected = deck.cards.toMutableMap()
            if (deck.startingLandSource == OtariaStartingLandSource.SEPARATE_STOCK) {
                deck.startingLands.forEach { expected[it] = (expected[it] ?: 0) + 1 }
            }
            withClue(deck.id) { all shouldBe expected }
        }
    }

    test("three to five players initialize with the human deck and authentic supplies") {
        for (count in 2..4) {
            val result = playtest.initialize(OtariaPlaytestRequest(aiOpponents = count, seed = 41))
            result.playerIds.size shouldBe count + 1
            for (id in result.playerIds) {
                result.state.getEntity(id)?.get<LifeTotalComponent>()?.life shouldBe 15
                result.state.getZone(ZoneKey(id, Zone.HAND)).size shouldBe 5
                result.state.getZone(ZoneKey(id, Zone.BATTLEFIELD)).size shouldBe 2
                val landNames = result.state.getZone(ZoneKey(id, Zone.BATTLEFIELD))
                    .map { result.state.getEntity(it)?.get<CardComponent>()?.name }
                result.state.getZone(ZoneKey(id, Zone.LIBRARY)).size shouldBe
                    if (landNames == listOf("Wastes", "Wastes")) 15 else 13
            }
            val mode = result.state.otaria.shouldNotBeNull()
            mode.players.size shouldBe count + 1
            mode.colorPiles.values.all { it.cards.size == 55 } shouldBe true
        }
    }

    test("every full-AI seat ignores human booster preferences and uses seeded independent rolls") {
        fun decks(request: OtariaPlaytestRequest): List<Map<String, Int>> {
            val result = playtest.initialize(request)
            return result.playerIds.map { id ->
                listOf(Zone.HAND, Zone.LIBRARY, Zone.BATTLEFIELD)
                    .flatMap { result.state.getZone(ZoneKey(id, it)) }
                    .map { requireNotNull(result.state.getEntity(it)?.get<CardComponent>()).name }
                    .groupingBy { it }.eachCount()
            }
        }
        val request = OtariaPlaytestRequest(fullAi = true, aiOpponents = 4, seed = 41)
        val randomDecks = decks(request)
        decks(request.copy(color = OtariaStartingColor.OTHER, boosterId = "J22:Urza's")) shouldBe randomDecks
        (randomDecks.distinct().size > 1) shouldBe true
    }

    test("launch creates one reconnectable human identity and every AI seat for all supported sizes") {
        for (count in 2..4) {
            val response = post("""{"playerName":"Human explorer","aiOpponents":$count,"color":"GREEN","seed":41}""")
            response.statusCode() shouldBe 200
            val body = json.parseToJsonElement(response.body()).jsonObject
            val gameId = body.getValue("sessionId").jsonPrimitive.content
            try {
                val roster = body.getValue("players").jsonArray.map { it.jsonObject }
                roster.size shouldBe count + 1
                val human = roster.first()
                val humanToken = human.getValue("token").jsonPrimitive.content
                (humanToken != "(AI)") shouldBe true
                sessions.getIdentityByToken(humanToken)?.playerId shouldBe EntityId(human.getValue("playerId").jsonPrimitive.content)
                val game = games.findById(gameId).shouldNotBeNull()
                game.maxPlayers shouldBe count + 1
                game.getPlayers().size shouldBe count
                roster.drop(1).forEach { seat ->
                    seat.getValue("token").jsonPrimitive.content shouldBe "(AI)"
                    ai.isAiPlayer(EntityId(seat.getValue("playerId").jsonPrimitive.content)) shouldBe true
                }
                game.getStateSnapshot().shouldNotBeNull().otaria.shouldNotBeNull()
            } finally {
                ai.cleanupGame(gameId)
            }
        }
    }

    test("invalid opponent counts and names are rejected before creating a table") {
        for (body in listOf(
            """{"aiOpponents":1}""",
            """{"aiOpponents":5}""",
            """{"playerName":"   "}""",
            """{"playerName":"${"x".repeat(41)}"}""",
            """{"boosterId":"does-not-exist"}""",
            """{"color":"GREEN","boosterId":"J22:Urza's"}""",
        )) {
            val response = post(body)
            response.statusCode() shouldBe 400
            json.parseToJsonElement(response.body()).jsonObject.getValue("errors").jsonArray.isNotEmpty() shouldBe true
        }
    }

    test("Otaria invite lobby seats multiple humans, requires readiness, and fills vacant seats with AI") {
        val create = call(
            "POST",
            "/api/dev/otaria/lobbies",
            """{"playerName":"Host explorer","tableSize":4,"seatIndex":2,"color":"GREEN","seed":41}""",
        )
        create.statusCode() shouldBe 200
        val host = json.parseToJsonElement(create.body()).jsonObject
        val lobbyId = host.getValue("lobbyId").jsonPrimitive.content
        val hostToken = host.getValue("memberToken").jsonPrimitive.content
        host.getValue("openSeats").jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("0", "1", "3")
        host.getValue("players").jsonArray.first().jsonObject.getValue("host").jsonPrimitive.boolean shouldBe true
        host.getValue("players").jsonArray.first().jsonObject.getValue("you").jsonPrimitive.boolean shouldBe true

        val join = call(
            "POST",
            "/api/dev/otaria/lobbies/$lobbyId/join",
            """{"playerName":"Guest explorer","seatIndex":0,"color":"BLUE"}""",
        )
        join.statusCode() shouldBe 200
        val guest = json.parseToJsonElement(join.body()).jsonObject
        val guestToken = guest.getValue("memberToken").jsonPrimitive.content
        guest.getValue("players").jsonArray.first { it.jsonObject.getValue("you").jsonPrimitive.boolean }
            .jsonObject.getValue("name").jsonPrimitive.content shouldBe "Guest explorer"
        guest.getValue("players").jsonArray.map { it.jsonObject.getValue("seatIndex").jsonPrimitive.content } shouldBe
            listOf("0", "2")

        call("PUT", "/api/dev/otaria/lobbies/$lobbyId/ready", """{"memberToken":"$hostToken","ready":true}""")
            .statusCode() shouldBe 200
        call("POST", "/api/dev/otaria/lobbies/$lobbyId/start", """{"memberToken":"$hostToken"}""")
            .statusCode() shouldBe 400
        call("PUT", "/api/dev/otaria/lobbies/$lobbyId/ready", """{"memberToken":"$guestToken","ready":true}""")
            .statusCode() shouldBe 200
        val start = call("POST", "/api/dev/otaria/lobbies/$lobbyId/start", """{"memberToken":"$hostToken"}""")
        start.statusCode() shouldBe 200
        val started = json.parseToJsonElement(start.body()).jsonObject
        val gameId = started.getValue("gameSessionId").jsonPrimitive.content
        try {
            started.getValue("started").jsonPrimitive.content shouldBe "true"
            started.getValue("gameToken").jsonPrimitive.content shouldBe hostToken
            val guestState = call(
                "GET",
                "/api/dev/otaria/lobbies/$lobbyId?memberToken=$guestToken",
            )
            guestState.statusCode() shouldBe 200
            val guestView = json.parseToJsonElement(guestState.body()).jsonObject
            guestView.getValue("gameToken").jsonPrimitive.content shouldBe guestToken

            val game = games.findById(gameId).shouldNotBeNull()
            game.maxPlayers shouldBe 4
            game.getPlayers().size shouldBe 2
            game.getStateSnapshot().shouldNotBeNull().turnOrder.count(ai::isAiPlayer) shouldBe 2
        } finally {
            ai.cleanupGame(gameId)
        }
    }

    test("full AI controls every seat at fast speed and progresses without a human connection") {
        for (count in 2..4) {
            val response = post("""{"playerName":"AI explorer","aiOpponents":$count,"color":"GREEN","seed":41,"fullAi":true}""")
            response.statusCode() shouldBe 200
            val body = json.parseToJsonElement(response.body()).jsonObject
            val gameId = body.getValue("sessionId").jsonPrimitive.content
            try {
                val roster = body.getValue("players").jsonArray.map { it.jsonObject }
                roster.size shouldBe count + 1
                val game = games.findById(gameId).shouldNotBeNull()
                game.getPlayers().size shouldBe count + 1
                roster.forEach { seat ->
                    seat.getValue("token").jsonPrimitive.content shouldBe "(AI)"
                    ai.isAiPlayer(EntityId(seat.getValue("playerId").jsonPrimitive.content)) shouldBe true
                }
                game.getPlayers().forEach { player ->
                    (player.webSocketSession as AiWebSocketSession).thinkingDelayMs shouldBe 100L
                }
                game.buildSpectatorState().shouldNotBeNull()
                eventually(60.seconds) {
                    val state = game.getStateSnapshot().shouldNotBeNull()
                    withClue("Turn ${state.turnNumber}, step ${state.step}, pending ${state.pendingDecision}") {
                        (state.turnNumber >= 4 || state.gameOver) shouldBe true
                    }
                }
            } finally {
                ai.cleanupGame(gameId)
            }
        }
    }
})
