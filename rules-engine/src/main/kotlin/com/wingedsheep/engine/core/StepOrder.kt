package com.wingedsheep.engine.core

import com.wingedsheep.sdk.core.Step

/**
 * Relative position in a natural turn, without changing ordinary enum ordinals or RNG salts.
 * The two Otaria phases after the first main phase are alternatives, not consecutive steps.
 */
val Step.turnOrderIndex: Int
    get() = when (this) {
        Step.OTARIA_PREPARATION -> Step.UPKEEP.ordinal * 2 + 1
        Step.OTARIA_MOVEMENT, Step.OTARIA_EXPLORATION -> Step.PRECOMBAT_MAIN.ordinal * 2 + 1
        else -> ordinal * 2
    }
