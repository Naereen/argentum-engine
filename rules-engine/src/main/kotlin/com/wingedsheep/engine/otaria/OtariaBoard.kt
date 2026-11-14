package com.wingedsheep.engine.otaria

import com.wingedsheep.sdk.core.Color
import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable
data class OtariaPosition(val column: Int, val row: Int)

@Serializable
enum class OtariaLocation(
    val displayName: String,
    val position: OtariaPosition,
    private val threePlayers: Int,
    private val fourPlayers: Int,
    private val fivePlayers: Int,
) {
    KROSIAN_FOREST("Krosian Forest", OtariaPosition(6, 5), 4, 4, 6),
    ORDER_ENCAMPMENT("Order Encampment", OtariaPosition(7, 3), 2, 2, 4),
    SOUTHERN_PIT_ARENA("Southern Pit-Arena", OtariaPosition(7, 8), 7, 9, 11),
    BALSHAN_BAY("Balshan Bay", OtariaPosition(11, 4), 2, 3, 4),
    PARDIC_MOUNTAINS("Pardic Mountains", OtariaPosition(4, 7), 2, 4, 4),
    ORDER_SOUTHERN_ENCAMPMENT("Order Southern Encampment", OtariaPosition(6, 12), 2, 3, 4),
    CABAL_CITY("Cabal City", OtariaPosition(8, 7), 2, 4, 6),
    CEPHALID_SOUTHERN_PALACE("Cephalid Southern Palace", OtariaPosition(10, 11), 2, 2, 4);

    fun explorationLimit(playerCount: Int): Int = when (playerCount) {
        3 -> threePlayers
        4 -> fourPlayers
        5 -> fivePlayers
        else -> throw IllegalArgumentException("Explorer of Otaria requires 3 to 5 players")
    }
}

data class OtariaTile(
    val position: OtariaPosition,
    val color: Color,
    val location: OtariaLocation? = null,
)

/**
 * The playable hexes of the central board. The north and south boards hold piles, not hexes.
 * Columns are staggered downward on odd columns.
 */
object OtariaBoard {
    private val columns = listOf(
        "UUUUUUUUUUUUUUU",
        "UUUUUUUUUUUUUU",
        "UUUUUGRRUUUUUUU",
        "UUUUGWRRRGUUUU",
        "UUUWGGRRRRGGUUU",
        "UUWWGGRRRRGWUU",
        "UUWWGGBBRRGWWUU",
        "UUWWGGBBBBWWWU",
        "UUWWWGBBBBWWUUU",
        "UUWWUUBBBUUUUU",
        "UUUWUUBBUUUUUUU",
        "UUUUUUUUUUUUUU",
        "UUUUUUUUUUUUUUU",
    )

    val tiles: Map<OtariaPosition, OtariaTile> = run {
        val locations = OtariaLocation.entries.associateBy { it.position }
        check(locations.size == OtariaLocation.entries.size) { "Location stars must be distinct" }
        val result = buildMap {
            columns.forEachIndexed { column, terrain ->
                terrain.forEachIndexed { row, symbol ->
                    val position = OtariaPosition(column, row)
                    val color = checkNotNull(Color.fromSymbol(symbol)) { "Unknown terrain: $symbol" }
                    put(position, OtariaTile(position, color, locations[position]))
                }
            }
        }
        check(locations.keys.all(result::containsKey)) { "Every location must be on the map" }
        result
    }

    fun distance(from: OtariaPosition, to: OtariaPosition): Long {
        require(from in tiles && to in tiles) { "Both positions must be on the Otaria map" }
        val fromQ = from.column.toLong()
        val toQ = to.column.toLong()
        val fromR = from.row.toLong() - (fromQ - (fromQ and 1)) / 2
        val toR = to.row.toLong() - (toQ - (toQ and 1)) / 2
        val dq = fromQ - toQ
        val dr = fromR - toR
        return (abs(dq) + abs(dr) + abs(dq + dr)) / 2
    }

    fun neighbours(position: OtariaPosition): List<OtariaPosition> {
        require(position in tiles) { "Position must be on the Otaria map" }
        val q = position.column
        val r = position.row - (q - (q and 1)) / 2
        return listOf(
            q + 1 to r, q + 1 to r - 1, q to r - 1,
            q - 1 to r, q - 1 to r + 1, q to r + 1,
        ).map { (nextQ, nextR) ->
            OtariaPosition(nextQ, nextR + (nextQ - (nextQ and 1)) / 2)
        }.filter { it in tiles }
    }
}
