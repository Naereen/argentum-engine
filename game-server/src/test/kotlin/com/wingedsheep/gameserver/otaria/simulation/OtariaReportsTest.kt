package com.wingedsheep.gameserver.otaria.simulation

import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.OtariaDiscoveredTreasure
import com.wingedsheep.engine.otaria.*
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.util.Locale

class OtariaReportsTest : FunSpec({
    val bot = EntityId("bot-1")
    fun log(
        id: String = "match-1",
        status: String = "COMPLETED",
        winner: EntityId? = bot,
        turnActions: List<Int> = listOf(1, 3),
        decisions: List<OtariaDecisionPoint> = emptyList(),
        failure: OtariaSimulationFailure? = null,
    ): OtariaMatchLog {
        val before = OtariaResourceSnapshot(4, 2, 1, 15, emptyMap(), OtariaPosition(8, 7), true)
        val after = before.copy(librarySize = 5, reserveCount = 0, life = 13)
        var sequence = 0
        val turns = turnActions.mapIndexed { index, count ->
            OtariaTurnTelemetry(index + 1, bot, listOf(OtariaChosenAction(bot, OtariaTurnAction.PREPARE)),
                List(count) {
                    sequence++
                    OtariaActionTelemetry(
                        sequence, bot, PassPriority(bot), index + 1,
                        listOf(OtariaResourceChange(bot, before, after)),
                        if (sequence == 1) decisions else emptyList(),
                    )
                },
            )
        }
        return OtariaMatchLog(
            metadata = OtariaSimulationMetadata(
                id, "2026-11-13T05:00:00Z", 41, 3,
                mapTiles = emptyList(),
                seats = listOf(OtariaInitialSeat(
                    bot, "Test pack", listOf("Plains", "Plains", "Card | [name] *test*"),
                    listOf("Plains", "Plains"), "JUMPSTART", "DECK",
                    OtariaLocation.CABAL_CITY, before.position, OtariaSimulationAi("current"), "current",
                )),
                maxTurns = 500, maxActions = 1000, timeoutMinutes = 30,
            ),
            requestedSetup = OtariaSimulationConfig(),
            turns = turns, finalPlayers = mapOf(bot to after),
            outcome = OtariaSimulationOutcome(status, winner, turns.size, sequence, "DECK_EMPTY"),
            failure = failure,
        )
    }

    test("descriptive statistics pin min max mean median population variance and standard deviation") {
        val stats = OtariaDescriptiveStatistics.of(listOf(4.0, 1.0, 3.0, 2.0))
        stats.count shouldBe 4
        stats.min shouldBe 1.0
        stats.max shouldBe 4.0
        stats.mean shouldBe 2.5
        stats.median shouldBe 2.5
        stats.variance shouldBe 1.25
        stats.standardDeviation shouldBe (kotlin.math.sqrt(1.25) plusOrMinus 1e-12)
        OtariaDescriptiveStatistics.of(listOf(9.0, 1.0, 3.0)).median shouldBe 3.0
        val singleton = OtariaDescriptiveStatistics.of(listOf(17.0))
        singleton.mean shouldBe 17.0
        singleton.median shouldBe 17.0
        singleton.variance shouldBe 0.0
        singleton.standardDeviation shouldBe 0.0
        val constant = OtariaDescriptiveStatistics.of(List(4) { 100.0 })
        constant.variance shouldBe 0.0
        shouldThrow<IllegalArgumentException> { OtariaDescriptiveStatistics.of(listOf(Double.NaN)) }
        shouldThrow<IllegalArgumentException> { OtariaDescriptiveStatistics.of(listOf(Double.POSITIVE_INFINITY)) }
    }

    test("empty statistics use null rather than NaN and survive JSON serialization") {
        val stats = OtariaDescriptiveStatistics.of(emptyList())
        stats.count shouldBe 0
        stats.min shouldBe null
        stats.max shouldBe null
        stats.mean shouldBe null
        stats.median shouldBe null
        stats.variance shouldBe null
        stats.standardDeviation shouldBe null
        val statistics = OtariaBatchStatistics("empty", "empty.jsonl")
        val summary = statistics.summarize()
        summary.botVictoryRate shouldBe 0.0
        val json = OtariaBatchRunner.json.encodeToString(summary)
        OtariaBatchRunner.json.decodeFromString<OtariaBatchSummary>(json) shouldBe summary
        OtariaMarkdownReports.summary(summary, "matches").contains("| N/A |") shouldBe true
    }

    test("victory cohorts exclude failures truncated matches draws and unknown winner IDs") {
        val statistics = OtariaBatchStatistics("batch", "batch.jsonl")
        statistics.add(log(decisions = listOf(OtariaDecisionPoint("MIRARI_DISCOVERED", bot))))
        statistics.add(log(status = "FAILED", turnActions = listOf(2)))
        statistics.add(log(status = "TRUNCATED", turnActions = emptyList()))
        statistics.add(log(winner = null, turnActions = listOf(2)))
        statistics.add(log(winner = EntityId("encounter"), turnActions = listOf(1)))
        val summary = statistics.summarize()
        summary.matches shouldBe 5
        summary.completedMatches shouldBe 3
        summary.botVictories shouldBe 1
        summary.draws shouldBe 1
        summary.otherCompletedOutcomes shouldBe 1
        summary.failedMatches shouldBe 1
        summary.truncatedMatches shouldBe 1
        summary.botVictoryRate shouldBe 0.2
        summary.winsByBot shouldBe mapOf("bot-1" to 1)
        summary.decisionsByType shouldBe mapOf("MIRARI_DISCOVERED" to 1)
        summary.allMatches.actionsPerMatch.count shouldBe 5
        summary.allMatches.actionsPerMatch.mean shouldBe 1.8
        summary.allMatches.actionsPerTurn.count shouldBe 5
        summary.allMatches.actionsPerTurn.mean shouldBe 1.8
        summary.allMatches.meanActionsPerTurnPerMatch.count shouldBe 4
        summary.victoriousMatches.turnsPerMatch.mean shouldBe 2.0
        summary.victoriousMatches.actionsPerMatch.mean shouldBe 4.0
        summary.victoriousMatches.actionsPerTurn.variance shouldBe 1.0
        summary.victoriousMatches.meanActionsPerTurnPerMatch.mean shouldBe 2.0
        summary.outcomesByReason["COMPLETED: DECK_EMPTY"] shouldBe 3
        val markdown = OtariaMarkdownReports.summary(summary, "batch-matches")
        markdown.contains("Real bot victories: 1 (20.000%") shouldBe true
        markdown.contains("population variance") shouldBe true
        markdown.contains("Real bot victories only") shouldBe true
    }

    test("pooled turns and match mean ratios have different weightings") {
        val statistics = OtariaBatchStatistics("weighted", "weighted.jsonl")
        statistics.add(log(turnActions = listOf(1, 1, 1)))
        statistics.add(log(turnActions = listOf(9)))
        val summary = statistics.summarize()
        summary.allMatches.actionsPerTurn.mean shouldBe 3.0
        summary.allMatches.actionsPerTurn.median shouldBe 1.0
        summary.allMatches.meanActionsPerTurnPerMatch.mean shouldBe 5.0
    }

    test("inconsistent telemetry is rejected explicitly") {
        val original = log()
        shouldThrow<IllegalArgumentException> {
            OtariaBatchStatistics("invalid", "invalid.jsonl").add(original.copy(
                outcome = original.outcome.copy(totalActions = 99),
            ))
        }
    }

    test("English match reports include setup decks turn actions resources treasure and retreat outcomes") {
        val original = log(decisions = listOf(
            OtariaDecisionPoint("MIRARI_DISCOVERED", bot, treasure = OtariaDiscoveredTreasure.Card(EntityId("mirari"), "Mirari")),
            OtariaDecisionPoint("RETREAT_INVOKED", bot, retreat = OtariaRetreatPlan(listOf(
                OtariaRetreatContest(EntityId("opponent"), OtariaContest(listOf(OtariaContestRound(2, 6)))),
            ))),
            OtariaDecisionPoint("EMERGENCY_DECK_OUT_PREPARATION", bot),
        ))
        val markdown = OtariaMarkdownReports.match(original)
        listOf(
            "Winner: bot-1", "End reason: Deck empty", "Random seed: 41", "Starting home: Cabal City",
            "| Plains | 2 |", "Card \\| \\[name\\] \\*test\\*", "### Turn 1 - bot-1",
            "chose to prepare.", "Passed priority.", "| 4 -> 5 |", "discovered Mirari",
            "Retreat failed.", "2 vs 6", "at most four cards", "## Final player state",
        ).forEach { markdown.contains(it) shouldBe true }
    }

    test("failed and uninitialized reports preserve English diagnostics and safely fenced stack traces") {
        val failure = OtariaSimulationFailure(
            "java.lang.IllegalStateException", "bad | <tag>\nnext line", "trace\n```\nmore trace",
            PassPriority(bot), 7, "OTARIA_EXPLORATION", bot, "A decision",
        )
        val original = log(status = "FAILED", winner = null, turnActions = emptyList(), failure = failure)
        val uninitialized = original.copy(
            metadata = original.metadata.copy(seats = emptyList()),
            finalPlayers = emptyMap(),
        )
        val markdown = OtariaMarkdownReports.match(uninitialized)
        listOf(
            "Initialization did not finish", "Requested setup", "No engine actions were accepted",
            "no victory is inferred", "Failure diagnostics", "bad \\| &lt;tag&gt;<br>next line",
            "Attempted action (not counted as accepted)", "````text", "No initialized player state",
        ).forEach { markdown.contains(it) shouldBe true }
    }

    test("Markdown numeric formatting uses English decimal points regardless of default locale") {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            val statistics = OtariaBatchStatistics("locale", "locale.jsonl")
            statistics.add(log())
            val markdown = OtariaMarkdownReports.summary(statistics.summarize(), "matches")
            markdown.contains("100.000%") shouldBe true
            markdown.contains("100,000%") shouldBe false
        } finally {
            Locale.setDefault(previous)
        }
    }
})
