package com.wingedsheep.gameserver.otaria.simulation

import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.core.OtariaDiscoveredTreasure
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

internal object OtariaMarkdownReports {
    fun match(log: OtariaMatchLog): String = buildString {
        appendLine("# Explorer of: Otaria - Match report")
        appendLine()
        appendLine("- Match ID: ${text(log.metadata.matchId)}")
        appendLine("- Started at (UTC): ${text(log.metadata.timestamp)}")
        appendLine("- Random seed: ${log.metadata.seed}")
        appendLine("- Players: ${log.metadata.playerCount} (all AI)")
        appendLine("- Map: ${text(log.metadata.mapLayout)}; setup seed: ${log.metadata.mapSeed}")
        appendLine("- Limits: ${log.metadata.maxTurns} engine turns, ${log.metadata.maxActions} accepted actions")
        appendLine()
        appendLine("## Outcome")
        appendLine()
        appendLine("- Status: ${words(log.outcome.status)}")
        appendLine("- Winner: ${log.outcome.winnerId?.value?.let(::text) ?: "None"}")
        appendLine("- End reason: ${words(log.outcome.reason)}")
        appendLine("- Recorded turns: ${log.outcome.totalTurns}")
        appendLine("- Accepted actions: ${log.outcome.totalActions}")
        if (log.outcome.status != "COMPLETED") {
            appendLine("\nThis match did not reach a completed game outcome; no victory is inferred.")
        }
        appendLine()
        appendLine("## Initial setup")
        if (log.metadata.seats.isEmpty()) {
            appendLine("\nInitialization did not finish. Requested setup:")
            appendLine()
            code(OtariaBatchRunner.json.encodeToString(log.requestedSetup), "json")
        }
        log.metadata.seats.forEach { seat ->
            appendLine()
            appendLine("### ${text(seat.playerId.value)}")
            appendLine()
            appendLine("- Starting home: ${text(seat.startingLocation.displayName)}")
            appendLine("- Starting position: (${seat.position.column}, ${seat.position.row})")
            appendLine("- AI profile: ${text(seat.resolvedProfileId)}")
            appendLine("- AI overrides: hidden-information determinization = ${seat.ai.determinizeHiddenInformation ?: "profile default"}; " +
                "card intent = ${seat.ai.useCardIntent ?: "profile default"}")
            appendLine("- Deck: ${seat.boosterId?.let(::text) ?: "Custom"} (${words(seat.deckKind)})")
            appendLine("- Starting lands: ${seat.startingLands.joinToString(", ", transform = ::text)}")
            appendLine("- Starting land source: ${words(seat.startingLandSource)}")
            appendLine()
            appendLine("| Card | Copies |")
            appendLine("|------|--------|")
            seat.cards.groupingBy { it }.eachCount().toSortedMap().forEach { (card, count) ->
                appendLine("| ${text(card)} | $count |")
            }
        }
        appendLine()
        appendLine("## Turn-by-turn play")
        appendLine()
        appendLine("Turns and accepted actions are engine telemetry, including encounter-side turns. " +
            "Resource changes across a turn boundary belong to the action that caused the transition. " +
            "IDs in action details identify entities; no unrecorded card names or AI intentions are inferred.")
        if (log.turns.isEmpty()) appendLine("\nNo engine actions were accepted.")
        log.turns.forEach { turn ->
            appendLine()
            appendLine("### Turn ${turn.turnNumber} - ${text(turn.activePlayerId.value)}")
            appendLine()
            appendLine("Accepted actions: ${turn.steps.size}.")
            turn.chosenActions.forEach {
                appendLine("- ${text(it.playerId.value)} chose to ${words(it.action.name).lowercase(Locale.ROOT)}.")
            }
            turn.steps.forEach { step ->
                appendLine()
                appendLine("#### Action ${step.sequence} - ${text(step.actorId.value)}")
                appendLine()
                appendLine(actionDescription(step.action))
                if (step.nextTurnNumber != turn.turnNumber) {
                    appendLine("The engine advanced to turn ${step.nextTurnNumber}.")
                }
                step.decisions.forEach { appendLine("- ${decisionDescription(it)}") }
                val changes = step.changes.filter { it.before != it.after }
                if (changes.isEmpty()) appendLine("\nNo recorded player resource or position changes.")
                else {
                    appendLine()
                    appendLine("| Player | Library | Hand | Reserve | Life | Treasure tokens | Position | Active |")
                    appendLine("|--------|---------|------|---------|------|-----------------|----------|--------|")
                    changes.forEach { change ->
                        val old = change.before
                        val new = change.after
                        appendLine("| ${text(change.playerId.value)} | ${old.librarySize} -> ${new.librarySize} | " +
                            "${old.handSize} -> ${new.handSize} | ${old.reserveCount} -> ${new.reserveCount} | " +
                            "${old.life ?: "N/A"} -> ${new.life ?: "N/A"} | ${tokens(old)} -> ${tokens(new)} | " +
                            "${position(old)} -> ${position(new)} | ${old.active} -> ${new.active} |")
                    }
                }
                if (step.action !is PassPriority) {
                    appendLine()
                    appendLine("<details><summary>Exact action / decision details</summary>")
                    appendLine()
                    code(OtariaBatchRunner.json.encodeToString(step.action), "json")
                    appendLine("\n</details>")
                }
            }
        }
        appendLine()
        appendLine("## Final player state")
        appendLine()
        appendLine("| Player | Library | Hand | Reserve | Life | Treasure tokens | Position | Active |")
        appendLine("|--------|---------|------|---------|------|-----------------|----------|--------|")
        log.finalPlayers.forEach { (id, value) ->
            appendLine("| ${text(id.value)} | ${value.librarySize} | ${value.handSize} | ${value.reserveCount} | " +
                "${value.life ?: "N/A"} | ${tokens(value)} | ${position(value)} | ${value.active} |")
        }
        if (log.finalPlayers.isEmpty()) appendLine("\nNo initialized player state is available.")
        log.failure?.let {
            appendLine()
            appendLine("## Failure diagnostics")
            appendLine()
            appendLine("- Exception: ${text(it.type)}")
            appendLine("- Message: ${text(it.message)}")
            appendLine("- Turn: ${it.turnNumber ?: "Not initialized"}; step: ${it.step?.let(::words) ?: "N/A"}")
            appendLine("- Priority holder: ${it.priorityPlayerId?.value?.let(::text) ?: "None"}")
            appendLine("- Pending decision: ${it.pendingDecision?.let(::text) ?: "None"}")
            it.attemptedAction?.let { action ->
                appendLine("\nAttempted action (not counted as accepted):\n")
                code(OtariaBatchRunner.json.encodeToString(action), "json")
            }
            appendLine("\nStack trace:\n")
            code(it.stackTrace, "text")
        }
    }

    fun summary(summary: OtariaBatchSummary, reportsDirectory: String): String = buildString {
        appendLine("# Explorer of: Otaria - Batch statistics")
        appendLine()
        appendLine("Batch: ${text(summary.batchId)}")
        appendLine()
        appendLine("Raw telemetry: [JSONL](./${summary.jsonlFile}). " +
            "English match reports: [directory](./$reportsDirectory/).")
        appendLine()
        appendLine("## Outcomes")
        appendLine()
        appendLine("- Matches run: ${summary.matches}")
        appendLine("- Completed matches: ${summary.completedMatches}")
        appendLine("- Real bot victories: ${summary.botVictories} (${number(summary.botVictoryRate * 100)}% of all matches)")
        appendLine("- Draws: ${summary.draws}")
        appendLine("- Other completed outcomes: ${summary.otherCompletedOutcomes}")
        appendLine("- Failed matches: ${summary.failedMatches}")
        appendLine("- Truncated matches: ${summary.truncatedMatches}")
        appendLine()
        appendLine("A real bot victory requires COMPLETED status, no failure, and a winner ID belonging " +
            "to one of the match's initial AI seats. Failures and limit stops never count as wins.")
        appendLine()
        appendLine("## Wins by bot seat")
        appendLine()
        appendLine("| Bot seat | Wins |")
        appendLine("|----------|------|")
        summary.winsByBot.forEach { (bot, wins) -> appendLine("| ${text(bot)} | $wins |") }
        appendLine("\nSeat IDs are reused across matches; a seat's deck or AI profile can vary. These are not profile win-rate estimates.")
        appendLine()
        counts("End reasons", summary.outcomesByReason)
        counts("Observed decision flags", summary.decisionsByType)
        appendLine("## Descriptive statistics")
        appendLine()
        appendLine("Variance (stdVar) is population variance: sum of squared deviations / N. " +
            "Standard deviation is its square root. Empty populations are N/A; a singleton has zero variance. " +
            "All figures use accepted actions and recorded engine turns, including encounter turns. " +
            "Incomplete matches contribute only their observed prefix to the all-matches cohort.")
        appendLine()
        cohort("All matches (including failed / truncated prefixes)", summary.allMatches)
        cohort("Real bot victories only", summary.victoriousMatches)
        appendLine("Actions per turn pools individual recorded turns (longer matches contribute more observations). " +
            "Mean actions per turn per match gives each nonempty match one observation; matches with no recorded " +
            "turns are excluded only from that ratio, not from turns/actions per match. " +
            "These cohorts are descriptive; incomplete matches are not comparable to finished durations.")
    }

    private fun StringBuilder.cohort(title: String, cohort: OtariaStatisticalCohort) {
        appendLine("### $title")
        appendLine()
        appendLine("| Measure | N | Min | Max | Mean | Median | Variance (stdVar) | Standard deviation |")
        appendLine("|---------|---|-----|-----|------|--------|-------------------|--------------------|")
        listOf(
            "Turns per match" to cohort.turnsPerMatch,
            "Total actions per match" to cohort.actionsPerMatch,
            "Actions per turn (pooled)" to cohort.actionsPerTurn,
            "Mean actions per turn per match" to cohort.meanActionsPerTurnPerMatch,
        ).forEach { (label, stats) ->
            appendLine("| $label | ${stats.count} | ${number(stats.min)} | ${number(stats.max)} | " +
                "${number(stats.mean)} | ${number(stats.median)} | ${number(stats.variance)} | ${number(stats.standardDeviation)} |")
        }
        appendLine()
    }

    private fun StringBuilder.counts(title: String, counts: Map<String, Int>) {
        appendLine("## $title")
        appendLine()
        appendLine("| Observation | Count |")
        appendLine("|-------------|-------|")
        counts.forEach { (key, count) -> appendLine("| ${words(key)} | $count |") }
        if (counts.isEmpty()) appendLine("\nNone recorded.")
        appendLine()
    }

    private fun actionDescription(action: GameAction): String = when (action) {
        is PassPriority -> "Passed priority."
        is SubmitDecision -> "Answered an engine decision. The exact response is recorded below."
        else -> {
            val type = OtariaBatchRunner.json.encodeToJsonElement(GameAction.serializer(), action)
                .jsonObject.getValue("type").jsonPrimitive.content
            "Performed ${text(type.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase(Locale.ROOT))}. " +
                "Parameters are recorded below."
        }
    }

    private fun decisionDescription(point: OtariaDecisionPoint): String {
        val subject = text(point.playerId.value)
        val event = when (point.type) {
            "MIRARI_DISCOVERED" -> "discovered Mirari"
            "TREASURE_ACQUIRED" -> "acquired treasure"
            "RETREAT_INVOKED" -> "invoked retreat"
            "UNSUPPORTED_CARD_SKIPPED" -> "skipped an unsupported exploration card"
            "EXPLORATION" -> "started exploring"
            "ENCOUNTER_STARTED" -> "started an encounter"
            "CREATURE_CAPTURED" -> "captured a creature"
            "PLAYER_ELIMINATED" -> "was eliminated"
            "EMERGENCY_DECK_OUT_PREPARATION" -> "prepared reserve cards with at most four cards left in the library"
            else -> "recorded ${words(point.type).lowercase(Locale.ROOT)}"
        }
        val treasure = when (val value = point.treasure) {
            is OtariaDiscoveredTreasure.Card -> " Card: ${text(value.name)} (${text(value.entityId.value)})."
            is OtariaDiscoveredTreasure.Token -> " Token: ${words(value.kind.name)}."
            null -> ""
        }
        val retreat = point.retreat?.let { plan ->
            " Retreat ${if (plan.succeeds) "succeeded" else "failed"}." +
                plan.contests.joinToString("") {
                    " Contest against ${text(it.opponentId.value)}: " +
                        it.contest.rounds.joinToString("; ") { round -> "${round.explorer} vs ${round.opponent}" } + "."
                }
        }.orEmpty()
        return "$subject $event.${point.detail?.let { " Detail: ${text(it)}." }.orEmpty()}$treasure$retreat"
    }

    private fun tokens(value: OtariaResourceSnapshot): String =
        value.treasureTokens.entries.joinToString(", ") { "${words(it.key.name)}: ${it.value}" }.ifEmpty { "None" }

    private fun position(value: OtariaResourceSnapshot): String = "(${value.position.column}, ${value.position.row})"
    private fun words(value: String): String = text(value.lowercase(Locale.ROOT).replace('_', ' '))
        .replaceFirstChar { it.titlecase(Locale.ROOT) }
    private fun number(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.3f", it) } ?: "N/A"
    private fun text(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\\", "\\\\").replace("|", "\\|")
        .replace("*", "\\*").replace("_", "\\_").replace("`", "\\`")
        .replace("[", "\\[").replace("]", "\\]").replace("\r", "").replace("\n", "<br>")

    private fun StringBuilder.code(value: String, language: String) {
        val fence = "`".repeat(maxOf(3, (Regex("`+").findAll(value).maxOfOrNull { it.value.length } ?: 0) + 1))
        appendLine("$fence$language")
        appendLine(value.trimEnd())
        appendLine(fence)
    }
}
