package com.wingedsheep.gameserver.otaria.simulation

import kotlinx.serialization.Serializable
import kotlin.math.sqrt

@Serializable
data class OtariaDescriptiveStatistics(
    val count: Int,
    val min: Double?,
    val max: Double?,
    val mean: Double?,
    val median: Double?,
    val variance: Double?,
    val standardDeviation: Double?,
) {
    companion object {
        fun of(values: List<Double>): OtariaDescriptiveStatistics {
            require(values.all { it.isFinite() }) { "Statistics require finite observations" }
            if (values.isEmpty()) return OtariaDescriptiveStatistics(0, null, null, null, null, null, null)
            val sorted = values.sorted()
            var mean = 0.0
            var squaredDeviations = 0.0
            values.forEachIndexed { index, value ->
                val delta = value - mean
                mean += delta / (index + 1)
                squaredDeviations += delta * (value - mean)
            }
            val middle = sorted.size / 2
            val median = if (sorted.size % 2 == 0) sorted[middle - 1] / 2 + sorted[middle] / 2 else sorted[middle]
            val variance = (squaredDeviations / values.size).coerceAtLeast(0.0)
            return OtariaDescriptiveStatistics(
                values.size, sorted.first(), sorted.last(), mean, median, variance, sqrt(variance),
            )
        }
    }
}

@Serializable
data class OtariaStatisticalCohort(
    val turnsPerMatch: OtariaDescriptiveStatistics,
    val actionsPerMatch: OtariaDescriptiveStatistics,
    val actionsPerTurn: OtariaDescriptiveStatistics,
    val meanActionsPerTurnPerMatch: OtariaDescriptiveStatistics,
)

@Serializable
data class OtariaBatchSummary(
    val schemaVersion: Int = 1,
    val batchId: String,
    val jsonlFile: String,
    val matches: Int,
    val completedMatches: Int,
    val botVictories: Int,
    val draws: Int,
    val otherCompletedOutcomes: Int,
    val failedMatches: Int,
    val truncatedMatches: Int,
    val botVictoryRate: Double,
    val winsByBot: Map<String, Int>,
    val outcomesByReason: Map<String, Int>,
    val decisionsByType: Map<String, Int>,
    val allMatches: OtariaStatisticalCohort,
    val victoriousMatches: OtariaStatisticalCohort,
)

/** Keeps numeric observations for exact medians, never whole match logs or game states. */
internal class OtariaBatchStatistics(private val batchId: String, private val jsonlFile: String) {
    private class Cohort {
        val turns = mutableListOf<Double>()
        val actions = mutableListOf<Double>()
        val actionsPerTurn = mutableListOf<Double>()
        val matchMeans = mutableListOf<Double>()

        fun add(log: OtariaMatchLog) {
            turns.add(log.outcome.totalTurns.toDouble())
            actions.add(log.outcome.totalActions.toDouble())
            actionsPerTurn.addAll(log.turns.map { it.steps.size.toDouble() })
            if (log.turns.isNotEmpty()) {
                matchMeans.add(log.outcome.totalActions.toDouble() / log.turns.size)
            }
        }

        fun summarize() = OtariaStatisticalCohort(
            OtariaDescriptiveStatistics.of(turns),
            OtariaDescriptiveStatistics.of(actions),
            OtariaDescriptiveStatistics.of(actionsPerTurn),
            OtariaDescriptiveStatistics.of(matchMeans),
        )
    }

    private val all = Cohort()
    private val victorious = Cohort()
    private val statuses = mutableMapOf<String, Int>()
    private val wins = sortedMapOf<String, Int>()
    private val reasons = sortedMapOf<String, Int>()
    private val decisions = sortedMapOf<String, Int>()
    private var draws = 0
    private var otherCompleted = 0

    fun add(log: OtariaMatchLog) {
        val outcome = log.outcome
        require(outcome.status in setOf("COMPLETED", "FAILED", "TRUNCATED")) {
            "Unknown simulation status: ${outcome.status}"
        }
        require(outcome.totalTurns == log.turns.size &&
            outcome.totalActions.toLong() == log.turns.sumOf { it.steps.size.toLong() }) {
            "Match totals disagree with recorded telemetry: ${log.metadata.matchId}"
        }
        all.add(log)
        statuses[outcome.status] = (statuses[outcome.status] ?: 0) + 1
        val reason = "${outcome.status}: ${outcome.reason}"
        reasons[reason] = (reasons[reason] ?: 0) + 1
        log.metadata.seats.forEach { wins.putIfAbsent(it.playerId.value, 0) }
        if (outcome.status == "COMPLETED") {
            when {
                outcome.winnerId == null -> draws++
                log.failure == null && log.metadata.seats.any { it.playerId == outcome.winnerId } -> {
                    val winner = outcome.winnerId.value
                    wins[winner] = wins.getValue(winner) + 1
                    victorious.add(log)
                }
                else -> otherCompleted++
            }
        }
        log.turns.forEach { turn ->
            turn.steps.forEach { step ->
                step.decisions.forEach { decisions[it.type] = (decisions[it.type] ?: 0) + 1 }
            }
        }
    }

    fun summarize(): OtariaBatchSummary {
        val matches = all.turns.size
        val victories = victorious.turns.size
        return OtariaBatchSummary(
            batchId = batchId, jsonlFile = jsonlFile, matches = matches,
            completedMatches = statuses["COMPLETED"] ?: 0, botVictories = victories,
            draws = draws, otherCompletedOutcomes = otherCompleted,
            failedMatches = statuses["FAILED"] ?: 0, truncatedMatches = statuses["TRUNCATED"] ?: 0,
            botVictoryRate = if (matches == 0) 0.0 else victories.toDouble() / matches,
            winsByBot = wins.toMap(), outcomesByReason = reasons.toMap(), decisionsByType = decisions.toMap(),
            allMatches = all.summarize(), victoriousMatches = victorious.summarize(),
        )
    }
}
