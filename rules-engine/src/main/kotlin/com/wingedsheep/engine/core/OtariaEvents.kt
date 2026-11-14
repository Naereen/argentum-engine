package com.wingedsheep.engine.core

import com.wingedsheep.engine.otaria.OtariaExplorationSource
import com.wingedsheep.engine.otaria.OtariaLocation
import com.wingedsheep.engine.otaria.OtariaPileCard
import com.wingedsheep.engine.otaria.OtariaPosition
import com.wingedsheep.engine.otaria.OtariaReinforcementRoll
import com.wingedsheep.engine.otaria.OtariaTurnAction
import com.wingedsheep.engine.otaria.OtariaTreasureToken
import com.wingedsheep.engine.state.ObjectRef
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("OtariaSetupEvent")
data class OtariaSetupEvent(val startingLocations: Map<EntityId, OtariaLocation>) : GameEvent

@Serializable
@SerialName("OtariaTurnActionChosenEvent")
data class OtariaTurnActionChosenEvent(val playerId: EntityId, val action: OtariaTurnAction) : GameEvent

@Serializable
@SerialName("OtariaEncounterManaAddedEvent")
data class OtariaEncounterManaAddedEvent(
    val actorId: EntityId,
    val creature: com.wingedsheep.engine.state.ObjectRef,
    val color: Color,
) : GameEvent

/** A variant rule roll, not a Magic die roll. */
@Serializable
@SerialName("OtariaReinforcementRolledEvent")
data class OtariaReinforcementRolledEvent(
    val actorId: EntityId,
    val roll: OtariaReinforcementRoll,
) : GameEvent

/** The support hand is private to the encounter's manager. */
@Serializable
@SerialName("OtariaEncounterSupportHandCreatedEvent")
data class OtariaEncounterSupportHandCreatedEvent(
    val actorId: EntityId,
    val cardIds: List<EntityId>,
) : GameEvent

@Serializable
@SerialName("OtariaEncounterStartedEvent")
data class OtariaEncounterStartedEvent(
    val actorId: EntityId,
    val explorerId: EntityId,
    val managerId: EntityId,
    val primary: ObjectRef,
    val members: Set<ObjectRef>,
) : GameEvent

@Serializable
@SerialName("OtariaEncounterJoinedEvent")
data class OtariaEncounterJoinedEvent(val actorId: EntityId, val playerId: EntityId) : GameEvent

@Serializable
@SerialName("OtariaCreatureCapturedEvent")
data class OtariaCreatureCapturedEvent(val actorId: EntityId, val playerId: EntityId, val cardId: EntityId) : GameEvent

@Serializable
@SerialName("OtariaEncounterReturnedEvent")
data class OtariaEncounterReturnedEvent(val explorerId: EntityId, val source: OtariaExplorationSource) : GameEvent

@Serializable
@SerialName("OtariaEncounterEndedEvent")
data class OtariaEncounterEndedEvent(val actorId: EntityId) : GameEvent

@Serializable
@SerialName("OtariaRetreatDeterminedEvent")
data class OtariaRetreatDeterminedEvent(
    val playerId: EntityId,
    val plan: com.wingedsheep.engine.otaria.OtariaRetreatPlan,
) : GameEvent

@Serializable
@SerialName("OtariaStartingLandsPlacedEvent")
data class OtariaStartingLandsPlacedEvent(
    val playerId: EntityId,
    val cardIds: List<EntityId>,
) : GameEvent

@Serializable
@SerialName("OtariaPlayerMovedEvent")
data class OtariaPlayerMovedEvent(
    val playerId: EntityId,
    val from: OtariaPosition,
    val to: OtariaPosition,
) : GameEvent

/** Reserve identities must only be projected to their owner. */
@Serializable
@SerialName("OtariaPreparedEvent")
data class OtariaPreparedEvent(
    val playerId: EntityId,
    val cardIds: List<EntityId>,
) : GameEvent

/** The card identity can be private while a morph encounter is being prepared. */
@Serializable
@SerialName("OtariaExplorationStartedEvent")
data class OtariaExplorationStartedEvent(
    val explorerId: EntityId,
    val managerId: EntityId,
    val source: OtariaExplorationSource,
    val card: OtariaPileCard,
) : GameEvent

@Serializable
@SerialName("OtariaExplorationEmptyEvent")
data class OtariaExplorationEmptyEvent(
    val explorerId: EntityId,
    val source: OtariaExplorationSource,
) : GameEvent

@Serializable
@SerialName("OtariaExplorationPileShuffledEvent")
data class OtariaExplorationPileShuffledEvent(
    val source: OtariaExplorationSource,
    val cardCount: Int,
) : GameEvent

/** The card identity is private until a player-facing projection permits it. */
@Serializable
@SerialName("OtariaCardAddedToReserveEvent")
data class OtariaCardAddedToReserveEvent(
    val playerId: EntityId,
    val cardId: EntityId,
    val cardName: String,
) : GameEvent

@Serializable
sealed interface OtariaDiscoveredTreasure {
    @Serializable
    @SerialName("Card")
    data class Card(val entityId: EntityId, val name: String) : OtariaDiscoveredTreasure

    @Serializable
    @SerialName("Token")
    data class Token(val kind: OtariaTreasureToken) : OtariaDiscoveredTreasure
}

/** The treasure identity is private to the exploring player. */
@Serializable
@SerialName("OtariaDiscoveryTreasureResolvedEvent")
data class OtariaDiscoveryTreasureResolvedEvent(
    val explorerId: EntityId,
    val treasure: OtariaDiscoveredTreasure?,
) : GameEvent

/** The selected location is private to the exploring player. */
@Serializable
@SerialName("OtariaDiscoveryFinishedEvent")
data class OtariaDiscoveryFinishedEvent(
    val explorerId: EntityId,
    val location: OtariaLocation?,
) : GameEvent

@Serializable
@SerialName("OtariaEventCopiesCreatedEvent")
data class OtariaEventCopiesCreatedEvent(
    val explorerId: EntityId,
    val originalSourceId: EntityId,
    val cardName: String,
    val copySourceIds: List<EntityId>,
) : GameEvent

@Serializable
@SerialName("OtariaEventCopyFinishedEvent")
data class OtariaEventCopyFinishedEvent(
    val explorerId: EntityId,
    val sourceId: EntityId,
) : GameEvent

@Serializable
@SerialName("OtariaEventFinishedEvent")
data class OtariaEventFinishedEvent(
    val explorerId: EntityId,
    val source: OtariaExplorationSource,
    val cardName: String,
) : GameEvent

@Serializable
@SerialName("OtariaInitiativeDeterminedEvent")
data class OtariaInitiativeDeterminedEvent(
    val actorId: EntityId,
    val explorerId: EntityId,
    val plan: com.wingedsheep.engine.otaria.OtariaInitiativePlan,
) : GameEvent

/**
 * The explored card has no script in this build (an event without an effect or a card missing from
 * the registry): it is discarded to its pile and the exploration ends instead of blocking the turn.
 */
@Serializable
@SerialName("OtariaCardSkippedEvent")
data class OtariaCardSkippedEvent(
    val explorerId: EntityId,
    val source: OtariaExplorationSource,
    val cardName: String,
) : GameEvent
