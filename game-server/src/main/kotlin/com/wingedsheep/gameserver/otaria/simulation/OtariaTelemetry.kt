package com.wingedsheep.gameserver.otaria.simulation

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.otaria.*
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.LifeTotalComponent
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.Serializable

@Serializable
data class OtariaResourceSnapshot(
    val librarySize: Int,
    val handSize: Int,
    val reserveCount: Int,
    val life: Int?,
    val treasureTokens: Map<OtariaTreasureToken, Int>,
    val position: OtariaPosition,
    val active: Boolean,
)

@Serializable
data class OtariaResourceChange(
    val playerId: EntityId,
    val before: OtariaResourceSnapshot,
    val after: OtariaResourceSnapshot,
)

@Serializable
data class OtariaDecisionPoint(
    val type: String,
    val playerId: EntityId,
    val detail: String? = null,
    val treasure: OtariaDiscoveredTreasure? = null,
    val retreat: OtariaRetreatPlan? = null,
)

@Serializable
data class OtariaActionTelemetry(
    val sequence: Int,
    val actorId: EntityId,
    val action: GameAction,
    val nextTurnNumber: Int,
    val changes: List<OtariaResourceChange>,
    val decisions: List<OtariaDecisionPoint>,
)

@Serializable
data class OtariaChosenAction(val playerId: EntityId, val action: OtariaTurnAction)

@Serializable
data class OtariaTurnTelemetry(
    val turnNumber: Int,
    val activePlayerId: EntityId,
    val chosenActions: List<OtariaChosenAction>,
    val steps: List<OtariaActionTelemetry>,
)

@Serializable
data class OtariaInitialSeat(
    val playerId: EntityId,
    val boosterId: String?,
    val cards: List<String>,
    val startingLands: List<String>,
    val deckKind: String,
    val startingLandSource: String,
    val startingLocation: OtariaLocation,
    val position: OtariaPosition,
    val ai: OtariaSimulationAi,
    val resolvedProfileId: String,
)

@Serializable
data class OtariaSimulationMetadata(
    val matchId: String,
    val timestamp: String,
    val seed: Long,
    val playerCount: Int,
    val mapSeed: Long = seed,
    val mapLayout: String = "otaria-v0.4",
    val mapTiles: List<OtariaMapTile>,
    val seats: List<OtariaInitialSeat>,
    val maxTurns: Int,
    val maxActions: Int,
    val timeoutMinutes: Int,
)

@Serializable
data class OtariaMapTile(val position: OtariaPosition, val color: String, val location: OtariaLocation?)

@Serializable
data class OtariaSimulationOutcome(
    val status: String,
    val winnerId: EntityId? = null,
    val totalTurns: Int,
    val totalActions: Int,
    val reason: String,
)

@Serializable
data class OtariaSimulationFailure(
    val type: String,
    val message: String,
    val stackTrace: String,
    val attemptedAction: GameAction?,
    val turnNumber: Int?,
    val step: String?,
    val priorityPlayerId: EntityId?,
    val pendingDecision: String?,
)

@Serializable
data class OtariaMatchLog(
    val schemaVersion: Int = 2,
    val metadata: OtariaSimulationMetadata,
    val requestedSetup: OtariaSimulationConfig,
    val turns: List<OtariaTurnTelemetry>,
    val finalPlayers: Map<EntityId, OtariaResourceSnapshot>,
    val outcome: OtariaSimulationOutcome,
    val failure: OtariaSimulationFailure? = null,
)

internal fun snapshots(state: GameState): Map<EntityId, OtariaResourceSnapshot> =
    requireNotNull(state.otaria).players.mapValues { (id, player) ->
        OtariaResourceSnapshot(
            state.getZone(id, Zone.LIBRARY).size,
            state.getZone(id, Zone.HAND).size,
            player.reserve.size,
            state.getEntity(id)?.get<LifeTotalComponent>()?.life,
            player.treasureTokens.toMap(),
            player.position,
            id in state.activePlayers,
        )
    }

internal class OtariaTelemetry {
    private val turns = linkedMapOf<Pair<Int, EntityId>, MutableList<OtariaActionTelemetry>>()
    private val choices = mutableMapOf<Pair<Int, EntityId>, MutableList<OtariaChosenAction>>()
    var end: GameEndedEvent? = null
        private set
    var actionCount: Int = 0
        private set

    fun record(before: GameState, result: ExecutionResult, action: GameAction) {
        val active = requireNotNull(before.activePlayerId) { "Otaria has no active player" }
        val key = before.turnNumber to active
        val old = snapshots(before)
        val new = snapshots(result.state)
        val decisions = decisionPoints(result.events).toMutableList()
        result.events.filterIsInstance<OtariaTurnActionChosenEvent>().forEach {
            choices.getOrPut(key) { mutableListOf() }.add(OtariaChosenAction(it.playerId, it.action))
        }
        // This records an observed low-library preparation, not an assertion about the bot's intent.
        result.events.filterIsInstance<OtariaPreparedEvent>().forEach {
            if (old.getValue(it.playerId).librarySize <= 4 && it.cardIds.isNotEmpty()) {
                decisions.add(OtariaDecisionPoint("EMERGENCY_DECK_OUT_PREPARATION", it.playerId))
            }
        }
        result.events.filterIsInstance<GameEndedEvent>().lastOrNull()?.let { end = it }
        actionCount++
        turns.getOrPut(key) { mutableListOf() }.add(
            OtariaActionTelemetry(
                actionCount, action.playerId, action, result.state.turnNumber,
                old.map { (id, value) -> OtariaResourceChange(id, value, new.getValue(id)) },
                decisions,
            ),
        )
    }

    fun finish(): List<OtariaTurnTelemetry> = turns.map { (key, steps) ->
        OtariaTurnTelemetry(key.first, key.second, choices[key].orEmpty().toList(), steps.toList())
    }
}

internal fun decisionPoints(events: List<GameEvent>): List<OtariaDecisionPoint> = events.mapNotNull {
    when (it) {
        is OtariaDiscoveryTreasureResolvedEvent -> if (it.treasure == null) null else OtariaDecisionPoint(
            if ((it.treasure as? OtariaDiscoveredTreasure.Card)?.name == OtariaSetup.MIRARI)
                "MIRARI_DISCOVERED" else "TREASURE_ACQUIRED",
            it.explorerId, treasure = it.treasure,
        )
        is OtariaRetreatDeterminedEvent -> OtariaDecisionPoint("RETREAT_INVOKED", it.playerId, retreat = it.plan)
        is OtariaCardSkippedEvent -> OtariaDecisionPoint("UNSUPPORTED_CARD_SKIPPED", it.explorerId, it.cardName)
        is OtariaExplorationStartedEvent -> OtariaDecisionPoint("EXPLORATION", it.explorerId, it.card.name)
        is OtariaEncounterStartedEvent -> OtariaDecisionPoint("ENCOUNTER_STARTED", it.explorerId, it.actorId.value)
        is OtariaCreatureCapturedEvent -> OtariaDecisionPoint("CREATURE_CAPTURED", it.playerId, it.cardId.value)
        is PlayerLostEvent -> OtariaDecisionPoint("PLAYER_ELIMINATED", it.playerId, it.reason.name)
        else -> null
    }
}
