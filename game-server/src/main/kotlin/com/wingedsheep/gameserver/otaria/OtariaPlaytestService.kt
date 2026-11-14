package com.wingedsheep.gameserver.otaria

import com.wingedsheep.engine.core.EngineServices
import com.wingedsheep.engine.core.Outcome
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.otaria.OtariaGameInitializer
import com.wingedsheep.engine.otaria.OtariaStartingDeckKind
import com.wingedsheep.engine.otaria.OtariaStartingPlayer
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.registry.PrintingRegistry
import com.wingedsheep.engine.state.components.identity.PlayerComponent
import com.wingedsheep.gameserver.ai.AiGameManager
import com.wingedsheep.gameserver.scenario.ScenarioBuildResult
import com.wingedsheep.gameserver.scenario.ScenarioMode
import com.wingedsheep.gameserver.scenario.ScenarioRequest
import com.wingedsheep.gameserver.scenario.ScenarioSeat
import com.wingedsheep.gameserver.scenario.ScenarioSessionFactory
import com.wingedsheep.sdk.model.CardEntry
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import org.springframework.stereotype.Service
import java.util.UUID
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

data class OtariaPlaytestRequest(
    val playerName: String? = null,
    val aiOpponents: Int? = null,
    val color: OtariaStartingColor? = null,
    val boosterId: String? = null,
    val seed: Long? = null,
    val fullAi: Boolean? = null,
)

data class OtariaLobbyCreateRequest(
    val playerName: String,
    val tableSize: Int,
    val seatIndex: Int,
    val color: OtariaStartingColor? = null,
    val boosterId: String? = null,
    val seed: Long? = null,
)

data class OtariaLobbyJoinRequest(
    val playerName: String,
    val seatIndex: Int,
    val color: OtariaStartingColor? = null,
    val boosterId: String? = null,
)

data class OtariaLobbyReadyRequest(val memberToken: String, val ready: Boolean)
data class OtariaLobbyMemberRequest(val memberToken: String)

data class OtariaLobbyPlayer(
    val seatIndex: Int,
    val name: String,
    val host: Boolean,
    val ready: Boolean,
    val you: Boolean,
)

data class OtariaLobbyResponse(
    val lobbyId: String,
    val tableSize: Int,
    val players: List<OtariaLobbyPlayer>,
    val openSeats: List<Int>,
    val aiSeatsOnStart: Int,
    val started: Boolean,
    val memberToken: String? = null,
    val gameSessionId: String? = null,
    val gameToken: String? = null,
)

data class OtariaPlaytestOptions(
    val aiEnabled: Boolean,
    val decks: List<OtariaPlaytestDeck>,
    val limitations: List<String>,
    val missingCards: List<String>,
)

/** Local integration harness, deliberately separate from the public lobby's finished modes. */
@Service
class OtariaPlaytestService(
    private val cards: CardRegistry,
    private val printings: PrintingRegistry,
    private val ai: AiGameManager,
    private val sessions: ScenarioSessionFactory,
) {
    private val startingPacks by lazy { OtariaStartingPacks(cards) }
    private val lobbies = ConcurrentHashMap<String, OtariaLobby>()

    private data class OtariaLobby(
        val id: String,
        val tableSize: Int,
        val seed: Long,
        val createdAt: Instant = Instant.now(),
        val players: MutableList<OtariaLobbyMember> = mutableListOf(),
        var game: com.wingedsheep.gameserver.scenario.ScenarioResponse? = null,
    )

    private data class OtariaLobbyMember(
        val token: String,
        val name: String,
        var seatIndex: Int,
        val isHost: Boolean,
        val color: OtariaStartingColor?,
        val boosterId: String?,
        var ready: Boolean = false,
    )

    fun options() = OtariaPlaytestOptions(
        aiEnabled = ai.aiEnabledToggle,
        decks = startingPacks.decks,
        limitations = listOf(
            "Experimental playtest, not a complete campaign. Movement, preparation, player combat and creature/boss encounters can be tested.",
            "Encounters use initiative, individual mana, direct creature combat, captures and retreat; their left-hand manager can be human or AI.",
            "Some exploration events and supply cards are not implemented. An unsupported exploration reports an error and may leave the turn waiting; start a new playtest to continue testing.",
            "Legendary encounters still use normal Magic targeting; the variant's special player/planeswalker targeting substitutions are not implemented.",
        ),
        missingCards = OtariaSuppliesCatalog.missingCardDefinitions(cards),
    )

    fun start(request: OtariaPlaytestRequest): com.wingedsheep.gameserver.scenario.ScenarioResponse {
        require(ai.aiEnabledToggle) { "AI is not enabled. Set game.ai.enabled=true." }
        val initialized = initialize(request)
        val names = initialized.playerIds.map { id ->
            requireNotNull(initialized.state.getEntity(id)?.get<com.wingedsheep.engine.state.components.identity.PlayerComponent>()).name
        }
        val started = EngineServices(cards, printings).turnManager.finishTurnStart(
            initialized.state, requireNotNull(initialized.state.activePlayerId), emptyList(),
        )
        check(started.outcome !is Outcome.Rejected) { "Could not begin the first Otaria turn: ${started.outcome}" }
        return sessions.createSession(
            ScenarioBuildResult(started.state, initialized.playerIds, cards),
            ScenarioRequest(
                players = names.map { ScenarioSeat(name = it) },
                mode = ScenarioMode.AI,
                aiPlayer = 2,
            ),
            allAi = request.fullAi == true,
            aiThinkingDelayMs = if (request.fullAi == true) 100L else null,
        )
    }

    fun createLobby(request: OtariaLobbyCreateRequest): OtariaLobbyResponse {
        require(request.tableSize in 3..5) { "Choose a table size between three and five players" }
        require(request.seatIndex in 0 until request.tableSize) { "Choose a seat at this table" }
        val name = validatePlayerName(request.playerName)
        validateDeckChoice(request.color, request.boosterId)
        lobbies.entries.removeIf { it.value.createdAt.isBefore(Instant.now().minusSeconds(6 * 60 * 60)) }

        val lobby = OtariaLobby(
            id = UUID.randomUUID().toString(),
            tableSize = request.tableSize,
            seed = request.seed ?: Random.nextLong(),
        )
        lobby.players += OtariaLobbyMember(
            token = newToken(),
            name = name,
            seatIndex = request.seatIndex,
            isHost = true,
            color = request.color,
            boosterId = request.boosterId,
        )
        lobbies[lobby.id] = lobby
        return lobbyView(lobby, lobby.players.single())
    }

    fun joinLobby(lobbyId: String, request: OtariaLobbyJoinRequest): OtariaLobbyResponse {
        val lobby = findLobby(lobbyId)
        synchronized(lobby) {
            require(lobby.game == null) { "This Otaria game has already started" }
            require(lobby.players.size < lobby.tableSize) { "This Otaria table is full" }
            require(request.seatIndex in 0 until lobby.tableSize) { "Choose a seat at this table" }
            require(lobby.players.none { it.seatIndex == request.seatIndex }) { "That seat is already occupied" }
            val name = validatePlayerName(request.playerName)
            validateDeckChoice(request.color, request.boosterId)
            val member = OtariaLobbyMember(
                token = newToken(),
                name = name,
                seatIndex = request.seatIndex,
                isHost = false,
                color = request.color,
                boosterId = request.boosterId,
            )
            lobby.players += member
            return lobbyView(lobby, member)
        }
    }

    fun getLobby(lobbyId: String, memberToken: String?): OtariaLobbyResponse {
        val lobby = findLobby(lobbyId)
        synchronized(lobby) {
            val member = memberToken?.let { requireMember(lobby, it) }
            return lobbyView(lobby, member)
        }
    }

    fun setLobbyReady(lobbyId: String, request: OtariaLobbyReadyRequest): OtariaLobbyResponse {
        val lobby = findLobby(lobbyId)
        synchronized(lobby) {
            require(lobby.game == null) { "This Otaria game has already started" }
            val member = requireMember(lobby, request.memberToken)
            member.ready = request.ready
            return lobbyView(lobby, member)
        }
    }

    fun startLobby(lobbyId: String, memberToken: String): OtariaLobbyResponse {
        val lobby = findLobby(lobbyId)
        synchronized(lobby) {
            requireMember(lobby, memberToken).also { require(it.isHost) { "Only the lobby host can start the game" } }
            require(lobby.game == null) { "This Otaria game has already started" }
            require(lobby.players.all { it.ready }) { "Every player must be ready before the host starts" }
            require(ai.aiEnabledToggle || lobby.players.size == lobby.tableSize) {
                "AI is disabled; every seat must be filled by a human before starting"
            }

            val initialized = initializeLobby(lobby)
            val names = initialized.playerIds.map { id ->
                requireNotNull(initialized.state.getEntity(id)?.get<PlayerComponent>()).name
            }
            val started = EngineServices(cards, printings).turnManager.finishTurnStart(
                initialized.state, requireNotNull(initialized.state.activePlayerId), emptyList(),
            )
            check(started.outcome !is Outcome.Rejected) {
                "Could not begin the first Otaria turn: ${started.outcome}"
            }
            lobby.game = sessions.createSession(
                ScenarioBuildResult(started.state, initialized.playerIds, cards),
                ScenarioRequest(
                    players = names.map { ScenarioSeat(name = it) },
                    mode = ScenarioMode.MULTIPLAYER,
                ),
                humanSeatTokens = lobby.players.associate { it.seatIndex to it.token },
            )
            return lobbyView(lobby, requireMember(lobby, memberToken))
        }
    }

    internal fun initialize(request: OtariaPlaytestRequest): com.wingedsheep.engine.core.InitializationResult {
        val aiOpponents = request.aiOpponents ?: 2
        val seed = request.seed ?: Random.nextLong()
        val random = Random(seed)
        require(aiOpponents in 2..4) { "Choose two, three or four AI opponents" }
        val name = (request.playerName ?: "Explorer").trim()
        require(name.isNotEmpty() && name.length <= 40) { "Choose a player name between 1 and 40 characters" }
        // Validate even an ignored full-AI selection so invalid client input never looks successful.
        if (request.boosterId != null) startingPacks.choose(request.color, request.boosterId, random)
        val players = (0..aiOpponents).map { index ->
            val human = index == 0 && request.fullAi != true
            val deck = startingPacks.choose(
                if (human) request.color else null,
                if (human) request.boosterId else null,
                random,
            )
            OtariaStartingPlayer(
                PlayerConfig(
                    name = if (index == 0) name else "Otaria AI $index",
                    deck = Deck(deck.cards.flatMap { (card, count) -> List(count) { card } }),
                    playerId = com.wingedsheep.sdk.model.EntityId("otaria-${UUID.randomUUID()}"),
                ),
                startingBasicLands = deck.startingLands.map { CardEntry(it) },
                deckKind = OtariaStartingDeckKind.JUMPSTART,
                startingLandSource = deck.startingLandSource,
            )
        }
        return OtariaGameInitializer(cards, printings).initialize(
            players, OtariaSuppliesCatalog.create(), seed,
        )
    }

    private fun initializeLobby(lobby: OtariaLobby): com.wingedsheep.engine.core.InitializationResult {
        val membersBySeat = lobby.players.associateBy { it.seatIndex }
        val random = Random(lobby.seed)
        val players = (0 until lobby.tableSize).map { index ->
            val member = membersBySeat[index]
            val deck = startingPacks.choose(member?.color, member?.boosterId, random)
            OtariaStartingPlayer(
                PlayerConfig(
                    name = member?.name ?: "Otaria AI ${index + 1}",
                    deck = Deck(deck.cards.flatMap { (card, count) -> List(count) { card } }),
                    playerId = EntityId("otaria-${UUID.randomUUID()}"),
                ),
                startingBasicLands = deck.startingLands.map { CardEntry(it) },
                deckKind = OtariaStartingDeckKind.JUMPSTART,
                startingLandSource = deck.startingLandSource,
            )
        }
        return OtariaGameInitializer(cards, printings).initialize(
            players, OtariaSuppliesCatalog.create(), lobby.seed,
        )
    }

    private fun lobbyView(lobby: OtariaLobby, member: OtariaLobbyMember?): OtariaLobbyResponse {
        val game = lobby.game
        return OtariaLobbyResponse(
            lobbyId = lobby.id,
            tableSize = lobby.tableSize,
            players = lobby.players.sortedBy { it.seatIndex }.map {
                OtariaLobbyPlayer(it.seatIndex, it.name, it.isHost, it.ready, it.token == member?.token)
            },
            openSeats = (0 until lobby.tableSize).filter { seat -> lobby.players.none { it.seatIndex == seat } },
            aiSeatsOnStart = lobby.tableSize - lobby.players.size,
            started = game != null,
            memberToken = member?.token,
            gameSessionId = game?.sessionId,
            gameToken = if (game == null || member == null) null else
                game.players?.getOrNull(member.seatIndex)?.token?.takeUnless { it == "(AI)" },
        )
    }

    private fun findLobby(lobbyId: String): OtariaLobby {
        val lobby = lobbies[lobbyId] ?: throw IllegalArgumentException("Otaria lobby not found")
        if (lobby.createdAt.isBefore(Instant.now().minusSeconds(6 * 60 * 60))) {
            lobbies.remove(lobbyId, lobby)
            throw IllegalArgumentException("Otaria lobby has expired")
        }
        return lobby
    }

    private fun requireMember(lobby: OtariaLobby, token: String): OtariaLobbyMember =
        lobby.players.find { it.token == token } ?: throw IllegalArgumentException("Invalid Otaria lobby member token")

    private fun validateDeckChoice(color: OtariaStartingColor?, boosterId: String?) {
        startingPacks.choose(color, boosterId, Random(0))
    }

    private fun validatePlayerName(value: String): String =
        value.trim().also { require(it.isNotEmpty() && it.length <= 40) { "Choose a player name between 1 and 40 characters" } }

    private fun newToken(): String = UUID.randomUUID().toString()
}
