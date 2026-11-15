package com.wingedsheep.gameserver.otaria.simulation

import com.wingedsheep.ai.engine.AiProfile
import com.wingedsheep.engine.otaria.OtariaPosition
import kotlinx.serialization.Serializable

@Serializable
data class OtariaSimulationAi(
    val profile: String = "live",
    val determinizeHiddenInformation: Boolean? = null,
    val useCardIntent: Boolean? = null,
) {
    fun resolve(): AiProfile {
        val base = when (profile) {
            "live" -> AiProfile.LIVE
            "current" -> AiProfile.CURRENT
            "legacy" -> AiProfile.LEGACY_V0
            else -> throw IllegalArgumentException("Unknown AI profile: $profile (live, current, legacy)")
        }
        return base.copy(
            determinizeHiddenInformation = determinizeHiddenInformation ?: base.determinizeHiddenInformation,
            useCardIntent = useCardIntent ?: base.useCardIntent,
        )
    }
}

@Serializable
data class OtariaSimulationSeat(
    val boosterId: String? = null,
    val cards: List<String>? = null,
    val startingLands: List<String>? = null,
    val deckKind: String = "CONSTRUCTED",
    val startingLandSource: String = "DECK",
    val startingLocation: String? = null,
    val position: OtariaPosition? = null,
    val ai: OtariaSimulationAi? = null,
)

@Serializable
data class OtariaSimulationConfig(
    val matches: Int = 10,
    val players: Int = 3,
    val seed: Long = 1,
    val maxTurns: Int = 500,
    val maxActions: Int = 100_000,
    val timeoutMinutes: Int = 30,
    val parallelism: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
    val output: String = "simulation-results/otaria",
    val randomMapPositions: Boolean = false,
    val verbose: Boolean = true,
    val ai: OtariaSimulationAi = OtariaSimulationAi(),
    val seats: List<OtariaSimulationSeat> = emptyList(),
) {
    fun validate() {
        require(matches > 0) { "matches must be positive" }
        require(players in 3..5) { "Otaria requires 3 to 5 players" }
        require(maxTurns > 0 && maxActions > 0) { "Simulation limits must be positive" }
        require(timeoutMinutes > 0) { "timeoutMinutes must be positive" }
        require(parallelism > 0) { "parallelism must be positive" }
        require(output.isNotBlank()) { "output must not be blank" }
        require(seats.isEmpty() || seats.size == players) { "Provide one seat setup per player" }
        ai.resolve()
        seats.forEach { it.ai?.resolve() }
    }
}
