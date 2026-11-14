package com.wingedsheep.engine.otaria

import com.wingedsheep.engine.core.ExecutionResult
import com.wingedsheep.engine.core.OtariaSetupEvent
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.PlayerComponent
import com.wingedsheep.sdk.core.Color

/**
 * Physical supplies before the random selection and shuffles. Counts include duplicate copies.
 * Mirari is supplied by the setup rule rather than as an entry in [treasures].
 */
data class OtariaSupplies(
    val bosses: List<String>,
    val discoveries: Int,
    val treasures: List<OtariaTreasure>,
    val locations: List<OtariaLocation> = OtariaLocation.entries.flatMap { location ->
        List(3) { location }
    },
    val colorPiles: Map<Color, List<OtariaPileCard>>,
    val encounterSupport: OtariaEncounterSupportTable = OtariaEncounterSupportTable(),
)

data class OtariaQuotas(val bosses: Int, val discoveries: Int, val otherTreasures: Int) {
    companion object {
        fun forPlayers(playerCount: Int): OtariaQuotas = when (playerCount) {
            3 -> OtariaQuotas(11, 12, 11)
            4 -> OtariaQuotas(17, 17, 16)
            5 -> OtariaQuotas(22, 22, 21)
            else -> throw IllegalArgumentException("Explorer of Otaria requires 3 to 5 players")
        }
    }
}

object OtariaSetup {
    const val MIRARI = "Mirari"
    const val DISCOVERY = "Discovery"

    /**
     * Attach a shuffled Otaria board to an initialized multiplayer game. The existing turn order
     * is the seating order; selecting the starting player belongs to game initialization.
     */
    fun initialize(state: GameState, supplies: OtariaSupplies): ExecutionResult {
        require(!state.gameOver && state.otaria == null) { "Otaria can only be set up once in an ongoing game" }
        val players = state.turnOrder
        val quotas = OtariaQuotas.forPlayers(players.size)
        require(players.distinct().size == players.size) { "Player seats must be distinct" }
        require(players == state.activePlayers && players.all { state.getEntity(it)?.has<PlayerComponent>() == true }) {
            "All seats must be active players"
        }
        require(supplies.bosses.all(::isCardName)) { "Boss names must not be blank or have surrounding whitespace" }
        require(supplies.bosses.size >= quotas.bosses) {
            "Not enough boss cards for ${players.size} players: need ${quotas.bosses}, got ${supplies.bosses.size}"
        }
        require(supplies.discoveries >= quotas.discoveries) { "Not enough discovery cards" }
        require(supplies.treasures.size >= quotas.otherTreasures) { "Not enough treasure cards" }
        require(supplies.treasures.all {
            it !is OtariaTreasure.Card || (isCardName(it.name) && !it.name.equals(MIRARI, ignoreCase = true))
        }) {
            "The other treasure supply must contain valid card names and exclude Mirari"
        }
        require(supplies.locations.groupingBy { it }.eachCount() == OtariaLocation.entries.associateWith { 3 }) {
            "The location pile must contain three copies of every starred location"
        }
        require(supplies.colorPiles.keys == Color.entries.toSet()) { "Exactly five color piles are required" }
        for ((color, pile) in supplies.colorPiles) {
            require(pile.all { isCardName(it.name) }) {
                "$color pile contains a blank card name or surrounding whitespace"
            }
            require(pile.size == 55 &&
                pile.count { it.kind == OtariaCardKind.NONCREATURE } == 10 &&
                pile.count { it.kind == OtariaCardKind.EVENT } == 8 &&
                pile.count { it.kind == OtariaCardKind.DISCOVERY } == 1 &&
                pile.count { it.kind == OtariaCardKind.CREATURE } == 36) {
                "$color pile must contain 10 noncreatures, 8 events, 1 discovery, and 36 creatures"
            }
        }

        var current = state
        val (stars, afterStars) = current.nextRandom { shuffle(OtariaLocation.entries) }
        current = afterStars
        val playerStates = players.zip(stars).associate { (player, location) ->
            player to OtariaPlayerState(startingLocation = location, position = location.position)
        }

        val (shuffledBosses, afterBosses) = current.nextRandom { shuffle(supplies.bosses) }
        current = afterBosses
        val starCards = shuffledBosses.take(quotas.bosses).map { OtariaPileCard(it, OtariaCardKind.BOSS) } +
            List(quotas.discoveries) { OtariaPileCard(DISCOVERY, OtariaCardKind.DISCOVERY) }
        val (starPile, afterStarPile) = current.nextRandom { shuffle(starCards) }
        current = afterStarPile

        val (shuffledTreasures, afterTreasures) = current.nextRandom { shuffle(supplies.treasures) }
        current = afterTreasures
        val (treasurePile, afterTreasurePile) = current.nextRandom {
            shuffle(shuffledTreasures.take(quotas.otherTreasures) + OtariaTreasure.Card(MIRARI))
        }
        current = afterTreasurePile

        val (locationPile, afterLocations) = current.nextRandom { shuffle(supplies.locations) }
        current = afterLocations
        val colorPiles = buildMap {
            for (color in Color.entries) {
                val (shuffled, afterColor) = current.nextRandom { shuffle(supplies.colorPiles.getValue(color)) }
                current = afterColor
                put(color, OtariaExplorationPile(shuffled))
            }
        }
        val otaria = OtariaState(
            players = playerStates,
            explorationsUsed = OtariaLocation.entries.associateWith { 0 },
            starPile = OtariaExplorationPile(starPile),
            colorPiles = colorPiles,
            treasurePile = treasurePile,
            locationPile = locationPile,
            encounterSupport = supplies.encounterSupport.snapshot(),
        )
        return ExecutionResult.success(
            current.copy(otaria = otaria),
            listOf(OtariaSetupEvent(playerStates.mapValues { it.value.startingLocation })),
        )
    }

    private fun isCardName(name: String): Boolean = name.isNotBlank() && name == name.trim()
}
