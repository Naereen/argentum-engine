package com.wingedsheep.engine.otaria

import com.wingedsheep.engine.state.ObjectRef
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.effects.Effect
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class OtariaCardKind {
    CREATURE,
    NONCREATURE,
    EVENT,
    DISCOVERY,
    BOSS,
}

@Serializable
enum class OtariaEventCopyRule {
    EACH_SPELL_CAST_THIS_TURN,
}

@Serializable
data class OtariaPileCard(
    val name: String,
    val kind: OtariaCardKind,
    val eventEffect: Effect? = null,
    val eventCopyRule: OtariaEventCopyRule? = null,
)

@Serializable
data class OtariaExplorationPile(
    val cards: List<OtariaPileCard>,
    val discard: List<OtariaPileCard> = emptyList(),
)

@Serializable
enum class OtariaTreasureToken {
    SCRAP,
    ROCK,
}

@Serializable
sealed interface OtariaTreasure {
    @Serializable
    @SerialName("Card")
    data class Card(val name: String) : OtariaTreasure

    @Serializable
    @SerialName("Token")
    data class Token(val kind: OtariaTreasureToken) : OtariaTreasure
}

@Serializable
data class OtariaPlayerState(
    val startingLocation: OtariaLocation,
    val position: OtariaPosition,
    val hasExploredLocationAwayFromStart: Boolean = false,
    val locationCards: List<OtariaLocation> = emptyList(),
    val reserve: List<EntityId> = emptyList(),
    val treasureTokens: Map<OtariaTreasureToken, Int> = emptyMap(),
)

@Serializable
sealed interface OtariaExplorationSource {
    @Serializable
    @SerialName("Star")
    data class Star(val location: OtariaLocation) : OtariaExplorationSource

    @Serializable
    @SerialName("Terrain")
    data class Terrain(val color: Color) : OtariaExplorationSource
}

@Serializable
enum class OtariaExplorationStage {
    DRAWN,
    CHOOSING_LOCATION,
}

@Serializable
data class OtariaPendingExploration(
    val explorerId: EntityId,
    val managerId: EntityId,
    val source: OtariaExplorationSource,
    val card: OtariaPileCard,
    val stage: OtariaExplorationStage = OtariaExplorationStage.DRAWN,
)

@Serializable
data class OtariaManaSourceAssignment(
    val source: ObjectRef,
    val creature: ObjectRef,
)

/**
 * An encounter is a separate side of the game, not another player's permanents. The map key in
 * [OtariaState.encounters] is its actor ID; [managerId] supplies input without owning that side.
 */
@Serializable
data class OtariaEncounter(
    val explorerId: EntityId,
    val managerId: EntityId,
    val position: OtariaPosition,
    val source: OtariaExplorationSource,
    val primaryId: EntityId,
    val members: Set<ObjectRef> = emptySet(),
    val participants: Set<EntityId> = setOf(explorerId),
    /** Noncreature support sources explicitly assigned to a creature's mana reserve. */
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val manaSources: List<OtariaManaSourceAssignment> = emptyList(),
    /** The drawn card is retained even if its battlefield characteristics later change. */
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val pileCard: OtariaPileCard? = null,
    val initiative: OtariaInitiativePlan? = null,
) {
    init {
        require(manaSources.map { it.source }.distinct().size == manaSources.size) {
            "An Otaria mana source cannot belong to multiple creatures"
        }
    }
}

@Serializable
enum class OtariaTurnAction {
    MOVE,
    EXPLORE,
    PREPARE,
    COMBAT,
}

@Serializable
data class OtariaTurn(
    val playerId: EntityId,
    val turnNumber: Int,
    val action: OtariaTurnAction,
    val movementAllowed: Boolean = true,
    val explorationFinished: Boolean = false,
    val encounterInitiative: OtariaInitiativeSide? = null,
    val encounterAction: OtariaTurnAction? = null,
)

/**
 * Private pile contents and player reserves are engine state, never a client DTO.
 */
@Serializable
data class OtariaState(
    val players: Map<EntityId, OtariaPlayerState>,
    val explorationsUsed: Map<OtariaLocation, Int>,
    val starPile: OtariaExplorationPile,
    val colorPiles: Map<Color, OtariaExplorationPile>,
    val treasurePile: List<OtariaTreasure>,
    val locationPile: List<OtariaLocation>,
    val pendingExploration: OtariaPendingExploration? = null,
    val mirariVictoryClaimed: Boolean = false,
    val turn: OtariaTurn? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val encounters: Map<EntityId, OtariaEncounter> = emptyMap(),
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val encounterSupport: OtariaEncounterSupportTable = OtariaEncounterSupportTable(),
)

/**
 * An encounter creature's "your graveyard" is the graveyard of the player who discovered it.
 * This affects card-text reads only; encounter-owned cards still use their actual owner zone.
 */
internal fun GameState.cardTextPlayerId(playerId: EntityId): EntityId =
    otaria?.encounters?.get(playerId)?.explorerId ?: playerId

internal fun GameState.cardTextZoneKey(playerId: EntityId, zone: Zone): ZoneKey =
    ZoneKey(if (zone == Zone.GRAVEYARD) cardTextPlayerId(playerId) else playerId, zone)
