package com.wingedsheep.gameserver.otaria.simulation

import com.wingedsheep.ai.engine.AIPlayer
import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.otaria.*
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.registry.PrintingRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.gameserver.otaria.OtariaStartingPacks
import com.wingedsheep.gameserver.otaria.OtariaSuppliesCatalog
import com.wingedsheep.sdk.model.CardEntry
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** No sessions, sockets or shared per-game AI caches: every match owns its engine and bots. */
class OtariaBatchRunner(
    private val cards: CardRegistry,
    private val printings: PrintingRegistry? = null,
    private val chooseAction: ((GameState, GameAction) -> GameAction)? = null,
    private val console: PrintStream = System.out,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    fun run(config: OtariaSimulationConfig): Path {
        config.validate()
        val directory = Path.of(config.output)
        Files.createDirectories(directory)
        val batchId = UUID.randomUUID().toString()
        val file = directory.resolve("batch-$batchId.jsonl")
        val reports = directory.resolve("batch-$batchId-matches")
        Files.createDirectory(reports)
        val statistics = OtariaBatchStatistics(batchId, file.fileName.toString())
        val parallelism = config.parallelism.coerceAtMost(config.matches)
        val executor = Executors.newFixedThreadPool(parallelism)
        val completions = ExecutorCompletionService<Pair<Int, OtariaMatchLog>>(executor)
        progress(
            config,
            "Batch: ${config.matches} matches, ${config.players} AI players, $parallelism parallel matches; " +
                "JSONL: ${file.toAbsolutePath()}",
        )
        progress(config, "English match reports: ${reports.toAbsolutePath()}")
        var nextToSubmit = 0
        fun submit(index: Int) {
            completions.submit(Callable {
                index to simulate(config, config.seed + index, "$batchId-${index + 1}", "${index + 1}/${config.matches}")
            })
        }
        try {
            while (nextToSubmit < parallelism) submit(nextToSubmit++)
            Files.newBufferedWriter(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { writer ->
                repeat(config.matches) { completed ->
                    val (matchIndex, log) = try {
                        completions.take().get()
                    } catch (exception: ExecutionException) {
                        throw exception.cause ?: exception
                    }
                    writer.write(json.encodeToString(log))
                    writer.newLine()
                    writer.flush()
                    Files.writeString(
                        reports.resolve("match-${matchIndex + 1}.md"), OtariaMarkdownReports.match(log),
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                    )
                    statistics.add(log)
                    progress(config, "Batch progress: ${completed + 1}/${config.matches} " +
                        "(${(completed + 1) * 100L / config.matches}%) written")
                    logger.info("Otaria match {}: {} ({})", log.metadata.matchId, log.outcome.status, log.outcome.reason)
                    if (nextToSubmit < config.matches) submit(nextToSubmit++)
                }
            }
        } finally {
            executor.shutdownNow()
        }
        val summary = statistics.summarize()
        val summaryMarkdown = OtariaMarkdownReports.summary(summary, reports.fileName.toString())
        val summaryFile = directory.resolve("batch-$batchId-summary.md")
        Files.writeString(summaryFile, summaryMarkdown, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        Files.writeString(
            directory.resolve("batch-$batchId-summary.json"), json.encodeToString(summary),
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
        )
        console.println(summaryMarkdown)
        console.println("[Otaria] Batch statistics: ${summaryFile.toAbsolutePath()}")
        return file
    }

    internal fun simulate(
        config: OtariaSimulationConfig,
        seed: Long,
        matchId: String,
        label: String = matchId,
    ): OtariaMatchLog {
        val telemetry = OtariaTelemetry()
        var state: GameState? = null
        var attempted: GameAction? = null
        var initialSeats = emptyList<OtariaInitialSeat>()
        val timestamp = Instant.now().toString()
        val startedNanos = nanoTime()
        val timeoutNanos = TimeUnit.MINUTES.toNanos(config.timeoutMinutes.toLong())
        fun deadlineExceeded() = nanoTime() - startedNanos >= timeoutNanos
        var failure: OtariaSimulationFailure? = null
        var status = "FAILED"
        var reason = "INITIALIZATION_FAILED"
        var timedOut = false
        progress(config, "Match $label starting: seed=$seed, players=${config.players}")
        try {
            val random = Random(seed)
            val packs = OtariaStartingPacks(cards)
            val selections = (0 until config.players).map { index ->
                val setup = config.seats.getOrNull(index) ?: OtariaSimulationSeat()
                require(setup.cards == null || setup.boosterId == null) { "Choose cards or boosterId, not both" }
                val pack = if (setup.cards == null) packs.choose(null, setup.boosterId, random) else null
                val names = setup.cards ?: requireNotNull(pack).cards.flatMap { (name, count) -> List(count) { name } }
                val lands = setup.startingLands ?: pack?.startingLands
                    ?: throw IllegalArgumentException("Custom decks require startingLands")
                val kind = if (pack != null) OtariaStartingDeckKind.JUMPSTART
                    else OtariaStartingDeckKind.valueOf(setup.deckKind)
                val source = pack?.startingLandSource ?: OtariaStartingLandSource.valueOf(setup.startingLandSource)
                val id = EntityId("otaria-ai-${index + 1}")
                OtariaStartingPlayer(
                    PlayerConfig("Otaria AI ${index + 1}", Deck(names), playerId = id),
                    lands.map { CardEntry(it) }, kind, source,
                ) to pack?.id
            }
            val services = EngineServices(cards, printings)
            val initialized = OtariaGameInitializer(cards, printings).initialize(
                selections.map { it.first }, OtariaSuppliesCatalog.create(), seed,
            )
            state = initialized.state
            val mode = requireNotNull(state.otaria)
            val fixedLocations = config.seats.mapNotNull { it.startingLocation?.let(OtariaLocation::valueOf) }
            require(fixedLocations.distinct().size == fixedLocations.size) { "Starting locations must be distinct" }
            val availableLocations = OtariaLocation.entries.filterNot { it in fixedLocations }.shuffled(random).toMutableList()
            val fixedPositions = config.seats.mapNotNull { it.position }
            require(fixedPositions.distinct().size == fixedPositions.size) { "Starting positions must be distinct" }
            val availablePositions = OtariaBoard.tiles.keys.filterNot { it in fixedPositions }.shuffled(random).toMutableList()
            val players = mode.players.mapValues { (id, player) ->
                val index = initialized.playerIds.indexOf(id)
                val setup = config.seats.getOrNull(index)
                val location = setup?.startingLocation?.let(OtariaLocation::valueOf)
                    ?: if (fixedLocations.isEmpty()) player.startingLocation else availableLocations.removeAt(0)
                val position = setup?.position
                    ?: if (config.randomMapPositions) availablePositions.removeAt(0) else location.position
                require(position in OtariaBoard.tiles) { "Starting position is outside the Otaria map: $position" }
                player.copy(startingLocation = location, position = position)
            }
            require(players.values.map { it.startingLocation }.distinct().size == config.players) {
                "Starting locations must be distinct"
            }
            require(players.values.map { it.position }.distinct().size == config.players) {
                "Starting positions must be distinct"
            }
            state = state.copy(otaria = mode.copy(players = players))
            initialSeats = initialized.playerIds.mapIndexed { index, id ->
                val selection = selections[index]
                val ai = config.seats.getOrNull(index)?.ai ?: config.ai
                val player = players.getValue(id)
                OtariaInitialSeat(
                    id, selection.second, selection.first.player.deck.cards,
                    selection.first.startingBasicLands.map { it.name }, selection.first.deckKind.name,
                    selection.first.startingLandSource.name, player.startingLocation, player.position, ai, ai.resolve().id,
                )
            }
            val bots = mutableMapOf<Pair<EntityId, EntityId>, AIPlayer>()
            fun bot(current: GameState, seat: EntityId): AIPlayer {
                val manager = current.actorFor(seat)
                val setup = initialSeats.single { it.playerId == manager }
                return bots.getOrPut(manager to seat) {
                    AIPlayer.create(cards, seat, setup.ai.resolve()).also { ai ->
                        initialSeats.filter { it.playerId != seat }.forEach {
                            ai.learnOpponentDecklist(it.playerId, it.cards.groupingBy { name -> name }.eachCount())
                        }
                    }
                }
            }
            val started = services.turnManager.finishTurnStart(state, requireNotNull(state.activePlayerId), emptyList())
            check(started.outcome !is Outcome.Rejected) { "First turn rejected: ${started.outcome}" }
            state = started.state
            val processor = ActionProcessor(services, computeUndo = false)
            var current = started.state
            var reportedTurn: Int? = null
            reason = "SIMULATION_FAILED"
            while (
                !current.gameOver && telemetry.actionCount < config.maxActions &&
                current.turnNumber <= config.maxTurns && !timedOut
            ) {
                if (deadlineExceeded()) {
                    timedOut = true
                    break
                }
                if (reportedTurn != current.turnNumber) {
                    reportedTurn = current.turnNumber
                    progress(config, "Match $label | Turn ${current.turnNumber} | active=${current.activePlayerId} | " +
                        "actions=${telemetry.actionCount}")
                    if (config.verbose) {
                        snapshots(current).forEach { (id, resources) ->
                            progress(config, "  $id: library=${resources.librarySize}, hand=${resources.handSize}, " +
                                "reserve=${resources.reserveCount}, life=${resources.life}, " +
                                "position=(${resources.position.column},${resources.position.row})")
                        }
                    }
                }
                attempted = null
                val decision = current.pendingDecision
                attempted = if (decision != null) {
                    SubmitDecision(decision.playerId, bot(current, decision.playerId).respondToDecision(current, decision))
                } else {
                    val seat = requireNotNull(current.priorityPlayerId) { "No decision or priority holder" }
                    bot(current, seat).chooseAction(current)
                }
                attempted = chooseAction?.invoke(current, attempted) ?: attempted
                if (deadlineExceeded()) {
                    timedOut = true
                    break
                }
                val result = processor.process(current, attempted).result
                check(result.outcome !is Outcome.Rejected) { "Action rejected: ${result.outcome}" }
                state = result.state
                telemetry.record(current, result, attempted)
                result.events.filterIsInstance<OtariaTurnActionChosenEvent>().forEach {
                    progress(config, "Match $label | Turn ${current.turnNumber} | ${it.playerId} chose ${it.action}")
                }
                current = result.state
                if (deadlineExceeded()) timedOut = true
            }
            status = if (!timedOut && current.gameOver) "COMPLETED" else "TRUNCATED"
            reason = when {
                timedOut -> "TIME_LIMIT"
                current.otaria?.mirariVictoryClaimed == true -> "MIRARI_RETURNED_TO_STELLAR_LOCATION"
                current.gameOver -> requireNotNull(telemetry.end) { "Game ended without a GameEndedEvent" }.reason.name
                telemetry.actionCount >= config.maxActions -> "ACTION_LIMIT"
                else -> "TURN_LIMIT"
            }
        } catch (exception: Exception) {
            status = "FAILED"
            failure = OtariaSimulationFailure(
                exception.javaClass.name, exception.message ?: exception.javaClass.simpleName,
                exception.stackTraceToString(), attempted, state?.turnNumber,
                state?.step?.name, state?.priorityPlayerId, state?.pendingDecision?.toString(),
            )
            logger.error("Otaria match $matchId failed at turn ${state?.turnNumber}: ${failure.message}", exception)
        }
        val turns = telemetry.finish()
        progress(config, "Match $label finished: $status ($reason), turns=${turns.size}, " +
            "actions=${telemetry.actionCount}, winner=${telemetry.end?.winnerId ?: "none"}")
        failure?.let { progress(config, "Match $label failure: ${it.type}: ${it.message}") }
        return OtariaMatchLog(
            metadata = OtariaSimulationMetadata(
                matchId, timestamp, seed, config.players,
                mapTiles = OtariaBoard.tiles.values.map { OtariaMapTile(it.position, it.color.name, it.location) },
                seats = initialSeats, maxTurns = config.maxTurns, maxActions = config.maxActions,
                timeoutMinutes = config.timeoutMinutes,
            ),
            requestedSetup = config,
            turns = turns,
            finalPlayers = state?.takeIf { it.otaria != null }?.let(::snapshots).orEmpty(),
            outcome = OtariaSimulationOutcome(
                status, telemetry.end?.winnerId?.takeIf { status == "COMPLETED" },
                turns.size, telemetry.actionCount, reason,
            ),
            failure = failure,
        )
    }

    private fun progress(config: OtariaSimulationConfig, message: String) {
        if (config.verbose) console.println("[Otaria] $message")
    }

    companion object {
        private val logger = LoggerFactory.getLogger(OtariaBatchRunner::class.java)
        val json = Json {
            encodeDefaults = true
            serializersModule = engineSerializersModule
        }
    }
}
