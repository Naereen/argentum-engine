package com.wingedsheep.engine.view

import com.wingedsheep.engine.state.GameState
import com.wingedsheep.sdk.model.EntityId

/** Private Otaria information is visible to the affected seat and its current input controller. */
object OtariaVisibility {
    fun canSeePrivateData(
        state: GameState,
        ownerId: EntityId,
        viewingPlayerId: EntityId,
        isSpectator: Boolean = false,
    ): Boolean = !isSpectator &&
        (ownerId == viewingPlayerId || state.actorFor(ownerId) == viewingPlayerId)
}
