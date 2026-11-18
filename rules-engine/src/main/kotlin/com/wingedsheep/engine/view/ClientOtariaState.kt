package com.wingedsheep.engine.view

import com.wingedsheep.engine.otaria.OtariaCardKind
import com.wingedsheep.engine.otaria.OtariaExplorationSource
import com.wingedsheep.engine.otaria.OtariaExplorationStage
import com.wingedsheep.engine.otaria.OtariaLocation
import com.wingedsheep.engine.otaria.OtariaPosition
import com.wingedsheep.engine.otaria.OtariaTreasureToken
import com.wingedsheep.engine.otaria.OtariaTurnAction
import com.wingedsheep.engine.otaria.OtariaInitiativeSide
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.Serializable

/** A viewer-specific view of the Otaria board, never the underlying shuffled supplies. */
@Serializable
data class ClientOtariaState(
    val tiles: List<ClientOtariaTile>,
    val players: List<ClientOtariaPlayer>,
    val locations: List<ClientOtariaLocation>,
    val starPile: ClientOtariaPile,
    val colorPiles: Map<Color, ClientOtariaPile>,
    val treasureCount: Int,
    val locationCount: Int,
    val encounters: List<ClientOtariaEncounter> = emptyList(),
    val pendingExploration: ClientOtariaExploration? = null,
    val turn: ClientOtariaTurn? = null,
)

@Serializable
data class ClientOtariaEncounter(
    val actorId: EntityId,
    val explorerId: EntityId,
    val managerId: EntityId,
    val position: OtariaPosition,
    val participants: Set<EntityId>,
    val initiative: OtariaInitiativeSide? = null,
)

@Serializable
data class ClientOtariaTurn(
    val action: OtariaTurnAction,
    val movementAllowed: Boolean,
    val explorationFinished: Boolean,
    val encounterInitiative: OtariaInitiativeSide? = null,
    val encounterAction: OtariaTurnAction? = null,
)

@Serializable
data class ClientOtariaTile(
    val position: OtariaPosition,
    val color: Color,
    val location: OtariaLocation? = null,
)

@Serializable
data class ClientOtariaPlayer(
    val playerId: EntityId,
    val startingLocation: OtariaLocation,
    val position: OtariaPosition,
    val reserveCount: Int,
    val privateData: ClientOtariaPrivateData? = null,
)

/** Only the player and a human controlling that player may see this information. */
@Serializable
data class ClientOtariaPrivateData(
    val reserve: List<ClientOtariaReserveCard>,
    val locationCards: List<OtariaLocation>,
    val treasureTokens: Map<OtariaTreasureToken, Int>,
)

@Serializable
data class ClientOtariaReserveCard(
    val id: EntityId,
    val name: String,
    val manaCost: String,
    val typeLine: String,
    val oracleText: String,
    val imageUri: String? = null,
    val cmc: Int,
    val cardTypes: List<String>,
    val colors: List<String>,
)

@Serializable
data class ClientOtariaLocation(
    val location: OtariaLocation,
    val used: Int,
    val limit: Int,
)

@Serializable
data class ClientOtariaPile(val remaining: Int, val discarded: Int)

@Serializable
data class ClientOtariaExploration(
    val explorerId: EntityId,
    val managerId: EntityId,
    val source: OtariaExplorationSource,
    val stage: OtariaExplorationStage,
    val faceDown: Boolean,
    val card: ClientOtariaExploredCard? = null,
)

@Serializable
data class ClientOtariaExploredCard(val name: String, val kind: OtariaCardKind, val imageUri: String? = null)
