package com.wingedsheep.gameserver.otaria

import com.wingedsheep.engine.core.EngineServices
import com.wingedsheep.engine.core.Outcome
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.otaria.OtariaGameInitializer
import com.wingedsheep.engine.otaria.OtariaStartingDeckKind
import com.wingedsheep.engine.otaria.OtariaStartingPlayer
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.registry.PrintingRegistry
import com.wingedsheep.gameserver.ai.AiGameManager
import com.wingedsheep.gameserver.scenario.ScenarioBuildResult
import com.wingedsheep.gameserver.scenario.ScenarioMode
import com.wingedsheep.gameserver.scenario.ScenarioRequest
import com.wingedsheep.gameserver.scenario.ScenarioSeat
import com.wingedsheep.gameserver.scenario.ScenarioSessionFactory
import com.wingedsheep.sdk.model.CardEntry
import com.wingedsheep.sdk.model.Deck
import org.springframework.stereotype.Service
import java.util.UUID
import kotlin.random.Random

data class OtariaPlaytestRequest(
    val playerName: String? = null,
    val aiOpponents: Int? = null,
    val color: OtariaStartingColor? = null,
    val boosterId: String? = null,
    val seed: Long? = null,
    val fullAi: Boolean? = null,
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
}
