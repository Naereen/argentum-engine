package com.wingedsheep.engine.view.projection

import com.wingedsheep.engine.otaria.OtariaBoard
import com.wingedsheep.engine.otaria.OtariaCardKind
import com.wingedsheep.engine.otaria.OtariaExplorationPile
import com.wingedsheep.engine.otaria.OtariaLocation
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.view.ClientOtariaExploration
import com.wingedsheep.engine.view.ClientOtariaEncounter
import com.wingedsheep.engine.view.ClientOtariaExploredCard
import com.wingedsheep.engine.view.ClientOtariaLocation
import com.wingedsheep.engine.view.ClientOtariaPile
import com.wingedsheep.engine.view.ClientOtariaPlayer
import com.wingedsheep.engine.view.ClientOtariaPrivateData
import com.wingedsheep.engine.view.ClientOtariaReserveCard
import com.wingedsheep.engine.view.ClientOtariaState
import com.wingedsheep.engine.view.ClientOtariaTile
import com.wingedsheep.engine.view.ClientOtariaTurn
import com.wingedsheep.engine.view.OtariaVisibility
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.KeywordAbility

internal class OtariaProjector(private val cardRegistry: CardRegistry) {
    private val tiles = OtariaBoard.tiles.values.map {
        ClientOtariaTile(it.position, it.color, it.location)
    }

    fun project(state: GameState, viewer: EntityId, isSpectator: Boolean): ClientOtariaState? {
        val mode = state.otaria ?: return null
        val players = mode.players.entries.sortedBy { it.key.value }.map { (playerId, player) ->
            val privateData = if (OtariaVisibility.canSeePrivateData(state, playerId, viewer, isSpectator)) {
                ClientOtariaPrivateData(
                    reserve = player.reserve.map { id ->
                        val card = checkNotNull(state.getEntity(id)?.get<CardComponent>()) {
                            "Otaria reserve contains a missing card: $id"
                        }
                        ClientOtariaReserveCard(
                            id = id,
                            name = card.name,
                            manaCost = card.manaCost.toString(),
                            typeLine = card.typeLine.toString(),
                            oracleText = card.oracleText,
                            imageUri = card.imageUri,
                        )
                    },
                    locationCards = player.locationCards,
                    treasureTokens = player.treasureTokens,
                )
            } else null
            ClientOtariaPlayer(playerId, player.startingLocation, player.position, player.reserve.size, privateData)
        }
        val locations = OtariaLocation.entries.map { location ->
            ClientOtariaLocation(location, mode.explorationsUsed.getValue(location), location.explorationLimit(mode.players.size))
        }
        val pending = mode.pendingExploration?.let { exploration ->
            val faceDown = when (exploration.card.kind) {
                OtariaCardKind.CREATURE, OtariaCardKind.BOSS -> {
                    val definition = cardRegistry.requireCard(exploration.card.name)
                    check(definition.typeLine.isCreature) {
                        "Otaria encounter is not a creature: ${exploration.card.name}"
                    }
                    definition.keywordAbilities.any { it is KeywordAbility.Morph }
                }
                else -> false
            }
            val mayIdentify = !faceDown ||
                OtariaVisibility.canSeePrivateData(state, exploration.managerId, viewer, isSpectator)
            ClientOtariaExploration(
                explorerId = exploration.explorerId,
                managerId = exploration.managerId,
                source = exploration.source,
                stage = exploration.stage,
                faceDown = faceDown,
                card = exploration.card.takeIf { mayIdentify }?.let {
                    ClientOtariaExploredCard(
                        it.name, it.kind,
                        if (it.kind == OtariaCardKind.EVENT) {
                            com.wingedsheep.engine.otaria.OtariaEventArtwork.forName(it.name)
                        } else null,
                    )
                },
            )
        }
        return ClientOtariaState(
            tiles = tiles,
            players = players,
            locations = locations,
            starPile = mode.starPile.toClientPile(),
            colorPiles = mode.colorPiles.mapValues { (_, pile) -> pile.toClientPile() },
            treasureCount = mode.treasurePile.size,
            locationCount = mode.locationPile.size,
            encounters = mode.encounters.entries.sortedBy { it.key.value }.map { (actor, encounter) ->
                ClientOtariaEncounter(
                    actor, encounter.explorerId, encounter.managerId, encounter.position,
                    encounter.participants, encounter.initiative?.first,
                )
            },
            pendingExploration = pending,
            turn = mode.turn?.takeIf {
                it.playerId == state.activePlayerId && it.turnNumber == state.turnNumber
            }?.let {
                ClientOtariaTurn(it.action, it.movementAllowed, it.explorationFinished, it.encounterInitiative, it.encounterAction)
            },
        )
    }

    private fun OtariaExplorationPile.toClientPile() = ClientOtariaPile(cards.size, discard.size)
}
