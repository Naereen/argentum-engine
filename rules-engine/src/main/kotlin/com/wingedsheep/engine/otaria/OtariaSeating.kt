package com.wingedsheep.engine.otaria

import com.wingedsheep.sdk.model.GameRng

object OtariaSeating {
    /**
     * Choose the first seat from a separate deterministic RNG stream. Pass the returned index and
     * the same seed to GameConfig; its explicit starting index rotates rather than shuffles seats.
     */
    fun startingPlayerIndex(playerCount: Int, seed: Long): Int {
        OtariaQuotas.forPlayers(playerCount)
        val (_, parent) = GameRng.seeded(seed).split()
        val (seatingRng, _) = parent.split()
        return seatingRng.nextInt(playerCount).first
    }
}
