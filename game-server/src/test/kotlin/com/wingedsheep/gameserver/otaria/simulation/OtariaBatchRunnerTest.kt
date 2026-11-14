package com.wingedsheep.gameserver.otaria.simulation

import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.gameserver.config.GameBeansConfig
import com.wingedsheep.gameserver.config.GameProperties
import com.wingedsheep.engine.otaria.OtariaBoard
import com.wingedsheep.engine.otaria.OtariaLocation
import com.wingedsheep.engine.otaria.OtariaPosition
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.decodeFromString
import java.nio.file.Files
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class OtariaBatchRunnerTest : FunSpec({
    val cards by lazy { GameBeansConfig(GameProperties()).cardRegistry() }
    val fast = OtariaSimulationAi(profile = "current")
    fun config(matches: Int = 1, maxActions: Int = 3) =
        OtariaSimulationConfig(matches = matches, seed = 41, ai = fast, maxActions = maxActions)

    test("seeded full-AI setup resets the game and records every seat and map tile") {
        val runner = OtariaBatchRunner(cards)
        val first = runner.simulate(config(), 41, "first")
        val second = runner.simulate(config(), 41, "second")
        first.outcome.status shouldBe "TRUNCATED"
        first.outcome.reason shouldBe "ACTION_LIMIT"
        first.outcome.totalActions shouldBe 3
        first.failure shouldBe null
        first.metadata.seats.size shouldBe 3
        first.metadata.mapSeed shouldBe 41
        first.metadata.mapTiles.size shouldBe OtariaBoard.tiles.size
        first.metadata.seats shouldBe second.metadata.seats
        first.turns shouldBe second.turns
        first.finalPlayers shouldBe second.finalPlayers
        first.metadata.seats.all { it.cards.size == 20 && it.startingLands.size == 2 } shouldBe true
        first.turns.flatMap { it.steps }.all { it.changes.size == 3 } shouldBe true
    }

    test("three to five players support fixed boosters, stellar homes and arbitrary map coordinates") {
        for (count in 3..5) {
            val seats = (0 until count).map { index ->
                OtariaSimulationSeat(
                    boosterId = "J22:Urza's",
                    startingLocation = OtariaLocation.entries[index].name,
                    position = OtariaPosition(0, index),
                    ai = if (index == 0) fast.copy(useCardIntent = true) else null,
                )
            }
            val log = OtariaBatchRunner(cards).simulate(config().copy(players = count, seats = seats), 41, "fixed-$count")
            log.outcome.status shouldBe "TRUNCATED"
            log.metadata.seats.map { it.position } shouldBe (0 until count).map { OtariaPosition(0, it) }
            log.metadata.seats.map { it.startingLocation } shouldBe OtariaLocation.entries.take(count)
            log.metadata.seats.all { it.boosterId == "J22:Urza's" } shouldBe true
            log.metadata.seats.first().ai.useCardIntent shouldBe true
        }
    }

    test("random map positions are seeded and partial fixed homes exclude collisions") {
        val setup = config().copy(
            randomMapPositions = true,
            seats = listOf(OtariaSimulationSeat(startingLocation = "CABAL_CITY"), OtariaSimulationSeat(), OtariaSimulationSeat()),
        )
        val runner = OtariaBatchRunner(cards)
        val first = runner.simulate(setup, 41, "random")
        first.outcome.status shouldBe "TRUNCATED"
        first.metadata.seats.first().startingLocation shouldBe OtariaLocation.CABAL_CITY
        first.metadata.seats.map { it.startingLocation }.distinct().size shouldBe 3
        first.metadata.seats.map { it.position }.distinct().size shouldBe 3
        first.metadata.seats shouldBe runner.simulate(setup, 41, "again").metadata.seats
    }

    test("JSONL flushes one parseable object per match and an exception does not stop later matches") {
        val directory = Files.createTempDirectory("otaria-batch-test-")
        var file: java.nio.file.Path? = null
        try {
            var calls = 0
            val terminal = ByteArrayOutputStream()
            PrintStream(terminal).use { console ->
                val runner = OtariaBatchRunner(cards, chooseAction = { _, action ->
                    calls++
                    if (calls == 3) {
                        throw IllegalStateException("injected simulation failure")
                    }
                    action
                }, console = console)
                file = runner.run(config(matches = 2, maxActions = 5).copy(output = directory.toString(), verbose = false))
            }
            val output = terminal.toString(Charsets.UTF_8)
            output.contains("# Explorer of: Otaria - Batch statistics") shouldBe true
            output.contains("Real bot victories: 0") shouldBe true
            output.contains("[Otaria] Match ") shouldBe false
            val resultFile = requireNotNull(file)
            val lines = Files.readAllLines(resultFile)
            lines.size shouldBe 2
            Files.readString(resultFile).endsWith("\n") shouldBe true
            val logs = lines.map { OtariaBatchRunner.json.decodeFromString<OtariaMatchLog>(it) }
            logs.map { it.metadata.seed } shouldBe listOf(41L, 42L)
            logs.map { it.outcome.status } shouldBe listOf("FAILED", "TRUNCATED")
            logs.first().failure?.message shouldBe "injected simulation failure"
            (logs.first().failure?.attemptedAction != null) shouldBe true
            logs.first().finalPlayers.size shouldBe 3
            logs.first().outcome.totalActions shouldBe 2
            logs.first().turns.isNotEmpty() shouldBe true
            logs.first().outcome.winnerId shouldBe null
            logs.map { it.metadata.matchId }.distinct().size shouldBe 2
            val stem = resultFile.fileName.toString().removeSuffix(".jsonl")
            val reports = directory.resolve("$stem-matches")
            for (index in logs.indices) {
                val report = Files.readString(reports.resolve("match-${index + 1}.md"))
                report.contains(logs[index].metadata.matchId) shouldBe true
                report.contains("# Explorer of: Otaria - Match report") shouldBe true
                report.contains("## Final player state") shouldBe true
            }
            Files.readString(reports.resolve("match-1.md")).contains("injected simulation failure") shouldBe true
            val summary = OtariaBatchRunner.json.decodeFromString<OtariaBatchSummary>(
                Files.readString(directory.resolve("$stem-summary.json")),
            )
            summary.matches shouldBe 2
            summary.failedMatches shouldBe 1
            summary.truncatedMatches shouldBe 1
            summary.botVictories shouldBe 0
            summary.allMatches.actionsPerMatch.min shouldBe 2.0
            summary.allMatches.actionsPerMatch.max shouldBe 5.0
            summary.allMatches.actionsPerMatch.mean shouldBe 3.5
            Files.readString(directory.resolve("$stem-summary.md")).contains("Real bot victories: 0") shouldBe true
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    test("rejected actions record the original failure state rather than silently passing") {
        val log = OtariaBatchRunner(cards, chooseAction = { _, _ -> PassPriority(EntityId("not-a-player")) })
            .simulate(config(), 41, "rejected")
        log.outcome.status shouldBe "FAILED"
        log.failure?.message?.contains("Action rejected") shouldBe true
        log.failure?.attemptedAction?.playerId shouldBe EntityId("not-a-player")
        log.outcome.totalActions shouldBe 0
        log.finalPlayers.size shouldBe 3
    }

    test("invalid per-match setup is an explicit initialization failure") {
        val setup = config().copy(seats = List(3) { OtariaSimulationSeat(boosterId = "missing-pack") })
        val log = OtariaBatchRunner(cards).simulate(setup, 41, "bad-deck")
        log.outcome.status shouldBe "FAILED"
        log.outcome.reason shouldBe "INITIALIZATION_FAILED"
        log.failure?.message?.contains("Unknown Jumpstart booster") shouldBe true
        log.requestedSetup shouldBe setup
    }

    test("custom starting decks play a whole real match to an engine outcome and respect the turn limit") {
        val setup = config(maxActions = 10_000).copy(
            maxTurns = 60,
            seats = List(3) {
                OtariaSimulationSeat(cards = List(20) { "Plains" }, startingLands = listOf("Plains", "Plains"), deckKind = "JUMPSTART")
            },
        )
        val log = OtariaBatchRunner(cards).simulate(setup, 41, "whole-match")
        log.failure shouldBe null
        log.outcome.status shouldBe "COMPLETED"
        log.outcome.reason shouldBe "LIFE_ZERO"
        (log.outcome.winnerId != null) shouldBe true
        (log.outcome.totalTurns > 3) shouldBe true
        log.turns.flatMap { it.chosenActions }.isNotEmpty() shouldBe true
        log.turns.flatMap { it.steps }.flatMap { it.decisions }.any { it.type == "PLAYER_ELIMINATED" } shouldBe true
        val limited = OtariaBatchRunner(cards).simulate(setup.copy(maxTurns = 1), 41, "turn-limit")
        limited.outcome.status shouldBe "TRUNCATED"
        limited.outcome.reason shouldBe "TURN_LIMIT"
        limited.outcome.totalTurns shouldBe 1
        limited.outcome.winnerId shouldBe null
        val statistics = OtariaBatchStatistics("real-match", "batch.jsonl")
        statistics.add(log)
        val summary = statistics.summarize()
        summary.botVictories shouldBe 1
        summary.victoriousMatches.turnsPerMatch.mean shouldBe log.outcome.totalTurns.toDouble()
        OtariaMarkdownReports.match(log).contains("Winner: ${log.outcome.winnerId}") shouldBe true
    }

    test("invalid batch sizes and AI profiles fail before opening an output file") {
        shouldThrow<IllegalArgumentException> { config().copy(players = 2).validate() }
        shouldThrow<IllegalArgumentException> { config().copy(matches = 0).validate() }
        shouldThrow<IllegalArgumentException> { config().copy(maxTurns = 0).validate() }
        shouldThrow<IllegalArgumentException> { config().copy(ai = OtariaSimulationAi("missing")).validate() }
    }

    test("terminal progress is verbose by default, follows each turn and chosen action, and supports quiet mode") {
        val bytes = ByteArrayOutputStream()
        PrintStream(bytes).use { console ->
            val runner = OtariaBatchRunner(cards, console = console)
            runner.simulate(config(), 41, "progress-test")
            val output = bytes.toString(Charsets.UTF_8)
            output.contains("Match progress-test starting: seed=41") shouldBe true
            output.contains("Turn 1 | active=") shouldBe true
            output.contains("library=") shouldBe true
            output.contains("chose ") shouldBe true
            output.contains("finished: TRUNCATED (ACTION_LIMIT)") shouldBe true
            bytes.reset()
            runner.simulate(config().copy(verbose = false), 41, "quiet-test")
            bytes.size() shouldBe 0
        }
    }
})
