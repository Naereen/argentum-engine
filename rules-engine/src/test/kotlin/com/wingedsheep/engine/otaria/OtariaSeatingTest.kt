package com.wingedsheep.engine.otaria

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.GameInitializer
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class OtariaSeatingTest : FunSpec({
    test("a seeded random first seat preserves the clockwise seating order") {
        for (count in 3..5) {
            val seats = (0 until count).map { EntityId("seat-$it") }
            val players = seats.map { PlayerConfig(it.value, Deck(emptyList()), playerId = it) }
            val chosen = mutableSetOf<Int>()
            for (seed in 0L..49L) {
                val index = OtariaSeating.startingPlayerIndex(count, seed)
                OtariaSeating.startingPlayerIndex(count, seed) shouldBe index
                chosen.add(index)
                val result = GameInitializer(CardRegistry()).initializeGame(GameConfig(
                    players = players,
                    startingHandSize = 0,
                    skipMulligans = true,
                    startingPlayerIndex = index,
                    seed = seed,
                ))
                val rotated = seats.drop(index) + seats.take(index)
                result.state.turnOrder shouldBe rotated
                result.state.activePlayerId shouldBe rotated.first()
                result.seed shouldBe seed
            }
            chosen shouldBe (0 until count).toSet()
        }
        shouldThrow<IllegalArgumentException> { OtariaSeating.startingPlayerIndex(2, 1) }
        shouldThrow<IllegalArgumentException> { OtariaSeating.startingPlayerIndex(6, 1) }
    }
})
