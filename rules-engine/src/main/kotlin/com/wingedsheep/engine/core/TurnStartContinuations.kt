package com.wingedsheep.engine.core

import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.otaria.OtariaEncounterEntryPlan
import com.wingedsheep.engine.otaria.OtariaPosition
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.EntersAsCopy
import com.wingedsheep.sdk.scripting.effects.Effect
import kotlinx.serialization.Serializable

@Serializable
data class TurnStartFollowUp(val effect: Effect, val context: EffectContext)

/** One turn occurrence, with the alternatives that were legal before it began. */
@Serializable
data class TurnStartReplacementContinuation(
    val nextPlayerId: EntityId,
    val options: List<TurnStartFollowUp>,
    val followUps: List<TurnStartFollowUp>,
    /** A mandatory skip applies as well (an extra turn under Ugin's Nexus): the turn can't simply begin. */
    val mustSkip: Boolean = false,
) : AnswerContinuation

/** Remaining first actions in the next actual turn, followed by that turn's untap step. */
@Serializable
data class FinishTurnStartContinuation(
    val activePlayerId: EntityId,
    val followUps: List<TurnStartFollowUp>,
) : AutomaticContinuation

@Serializable
data class OtariaTurnChoiceContinuation(val playerId: EntityId) : AnswerContinuation

@Serializable
internal data class FinishOtariaEncounterEntryContinuation(val actorId: EntityId) : AutomaticContinuation

@Serializable
internal data class OtariaEncounterInitiativeContinuation(val actorId: EntityId) : AnswerContinuation

@Serializable
data class OtariaEncounterManaChoiceContinuation(
    val actorId: EntityId,
    val creature: com.wingedsheep.engine.state.ObjectRef,
    val remaining: Int,
) : AnswerContinuation

@Serializable
data class OtariaEncounterEntryManaChoiceContinuation(
    val actorId: EntityId,
    val creature: com.wingedsheep.engine.state.ObjectRef,
    val remaining: Int,
) : AnswerContinuation

@Serializable
internal data class OtariaEncounterEntryChoiceContinuation(
    val plan: OtariaEncounterEntryPlan,
    val context: EffectContext,
    val entityId: EntityId,
    val question: EntersWithChoiceOnBattlefieldContinuation,
) : AnswerContinuation

@Serializable
internal data class OtariaEncounterEntryCopyContinuation(
    val plan: OtariaEncounterEntryPlan,
    val context: EffectContext,
    val entityId: EntityId,
    val replacement: EntersAsCopy,
) : AnswerContinuation

@Serializable
internal data class OtariaEncounterRetaliationContinuation(
    val creatureId: EntityId,
    val firstStrike: Boolean,
) : AnswerContinuation

@Serializable
data class OtariaMovementChoiceContinuation(
    val playerId: EntityId,
    val destinations: List<OtariaPosition>,
) : AnswerContinuation

@Serializable
data class OtariaPreparationChoiceContinuation(val playerId: EntityId) : AnswerContinuation

@Serializable
data class OtariaExplorationChoice(
    val exploreLocation: Boolean,
    val takeLand: Boolean,
)

@Serializable
data class OtariaExplorationChoiceContinuation(
    val playerId: EntityId,
    val choices: List<OtariaExplorationChoice>,
) : AnswerContinuation

@Serializable
data class OtariaDiscoveryChoiceContinuation(val playerId: EntityId) : AnswerContinuation

@Serializable
data class FinishOtariaEventContinuation(
    val explorerId: EntityId,
    val sourceId: EntityId,
) : AutomaticContinuation

@Serializable
data class ContinueOtariaEventEffectsContinuation(
    val explorerId: EntityId,
    val managerId: EntityId,
    val originalSourceId: EntityId,
    val sourceIds: List<EntityId>,
    val nextIndex: Int,
) : AutomaticContinuation

@Serializable
data class FinishOtariaEventCopyContinuation(
    val explorerId: EntityId,
    val sourceId: EntityId,
) : AutomaticContinuation
