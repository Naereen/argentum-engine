package com.wingedsheep.engine.otaria

import com.wingedsheep.engine.state.GameState
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.model.GameRng
import kotlinx.serialization.Serializable

@Serializable
data class OtariaContestRound(val explorer: Int, val opponent: Int) {
    init {
        require(explorer in 1..6 && opponent in 1..6) { "A contest uses two six-sided dice" }
    }
}

@Serializable
data class OtariaContest(val rounds: List<OtariaContestRound>) {
    init {
        require(rounds.isNotEmpty()) { "A contest must contain at least one roll" }
        require(rounds.dropLast(1).all { it.explorer == it.opponent }) {
            "Only a tie is rerolled"
        }
        require(rounds.last().explorer != rounds.last().opponent) {
            "A contest must end with a non-tied roll"
        }
    }

    val explorerWon: Boolean
        get() = rounds.last().explorer > rounds.last().opponent
}

@Serializable
enum class OtariaInitiativeSide {
    EXPLORER,
    ENCOUNTER,
}

@Serializable
data class OtariaInitiativePlan(
    val first: OtariaInitiativeSide,
    val contest: OtariaContest?,
) {
    init {
        require(
            if (contest == null) first == OtariaInitiativeSide.ENCOUNTER
            else (first == OtariaInitiativeSide.EXPLORER) == contest.explorerWon
        ) { "The initiative winner must agree with the contest" }
    }
}

@Serializable
data class OtariaRetreatContest(val opponentId: EntityId, val contest: OtariaContest)

@Serializable
data class OtariaRetreatPlan(val contests: List<OtariaRetreatContest>) {
    init {
        require(contests.map { it.opponentId }.distinct().size == contests.size) {
            "Each opponent can contest a retreat only once"
        }
    }

    val succeeds: Boolean
        get() = contests.all { it.contest.explorerWon }
}

/**
 * Plans Otaria's rule-specific dice contests without changing the game state.
 *
 * A host must publish these rolls as Otaria events when it applies the returned RNG. They must not
 * be emitted as Magic die-roll events: card abilities neither modify nor observe these rule rolls.
 * The encounter side is separate from the human who manages it.
 */
internal object OtariaContestPlanner {
    /**
     * The encountered permanent must already be on the battlefield. Haste is read from projected
     * characteristics, so a face-down creature has no printed haste unless an effect grants it.
     */
    fun initiative(state: GameState, encounteredCreature: EntityId): Pair<OtariaInitiativePlan, GameRng> {
        require(encounteredCreature in state.getBattlefield() &&
            state.projectedState.isCreature(encounteredCreature)) {
            "Initiative requires an encountered creature on the battlefield"
        }
        if (state.projectedState.hasKeyword(encounteredCreature, Keyword.HASTE)) {
            return OtariaInitiativePlan(OtariaInitiativeSide.ENCOUNTER, null) to state.rng
        }
        val (contest, advanced) = duel(state.rng)
        val first = if (contest.explorerWon) OtariaInitiativeSide.EXPLORER else OtariaInitiativeSide.ENCOUNTER
        return OtariaInitiativePlan(first, contest) to advanced
    }

    /**
     * [blockingOpponents] contains only opponents who do not allow the explorer to leave. Its order
     * is the order in which the independent contests are rolled. All contests are rolled, even if
     * an earlier one was lost; every one must be won. No opponents means an unopposed retreat.
     */
    fun retreat(rng: GameRng, blockingOpponents: List<EntityId>): Pair<OtariaRetreatPlan, GameRng> {
        require(blockingOpponents.distinct().size == blockingOpponents.size) {
            "Each opponent can contest a retreat only once"
        }
        var current = rng
        val contests = blockingOpponents.map { opponent ->
            val (contest, advanced) = duel(current)
            current = advanced
            OtariaRetreatContest(opponent, contest)
        }
        return OtariaRetreatPlan(contests) to current
    }

    private fun duel(rng: GameRng): Pair<OtariaContest, GameRng> {
        var current = rng
        val rounds = mutableListOf<OtariaContestRound>()
        do {
            val (explorer, afterExplorer) = current.rollOtariaDie(6)
            val (opponent, afterOpponent) = afterExplorer.rollOtariaDie(6)
            current = afterOpponent
            val round = OtariaContestRound(explorer, opponent)
            rounds.add(round)
        } while (round.explorer == round.opponent)
        return OtariaContest(rounds.toList()) to current
    }
}

internal fun GameRng.rollOtariaDie(sides: Int): Pair<Int, GameRng> {
    val (offset, advanced) = nextInt(sides)
    return offset + 1 to advanced
}
