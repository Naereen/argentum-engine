package com.wingedsheep.gameserver.otaria.simulation

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.otaria.*
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class OtariaTelemetryTest : FunSpec({
    val seat = EntityId("explorer")
    val card = EntityId("card")
    val mode = OtariaState(
        players = mapOf(seat to OtariaPlayerState(
            OtariaLocation.CABAL_CITY, OtariaLocation.CABAL_CITY.position, reserve = listOf(card),
        )),
        explorationsUsed = emptyMap(), starPile = OtariaExplorationPile(emptyList()),
        colorPiles = emptyMap(), treasurePile = emptyList(), locationPile = emptyList(),
    )
    val before = GameState(
        otaria = mode, turnNumber = 7, activePlayerId = seat, turnOrder = listOf(seat),
    )

    test("event-backed flags distinguish Mirari, treasures, retreat and elimination") {
        val events = listOf(
            OtariaDiscoveryTreasureResolvedEvent(seat, OtariaDiscoveredTreasure.Card(card, "Mirari")),
            OtariaDiscoveryTreasureResolvedEvent(seat, OtariaDiscoveredTreasure.Token(OtariaTreasureToken.ROCK)),
            OtariaDiscoveryTreasureResolvedEvent(seat, null),
            OtariaRetreatDeterminedEvent(seat, OtariaRetreatPlan(emptyList())),
            PlayerLostEvent(seat, GameEndReason.DECK_EMPTY),
        )
        decisionPoints(events).map { it.type } shouldBe listOf(
            "MIRARI_DISCOVERED", "TREASURE_ACQUIRED", "RETREAT_INVOKED", "PLAYER_ELIMINATED",
        )
        decisionPoints(events).first().treasure shouldBe OtariaDiscoveredTreasure.Card(card, "Mirari")
        decisionPoints(events)[1].treasure shouldBe OtariaDiscoveredTreasure.Token(OtariaTreasureToken.ROCK)
        decisionPoints(events)[2].retreat?.succeeds shouldBe true
    }

    test("preparation records exact before-after resources and observed emergency prevention") {
        val player = mode.players.getValue(seat)
        val after = before.copy(
            zones = mapOf(ZoneKey(seat, Zone.LIBRARY) to listOf(card)),
            otaria = mode.copy(players = mapOf(seat to player.copy(
                reserve = emptyList(), position = OtariaPosition(8, 8),
                treasureTokens = mapOf(OtariaTreasureToken.ROCK to 1),
            ))),
        )
        val telemetry = OtariaTelemetry()
        telemetry.record(before, ExecutionResult.success(after, listOf(
            OtariaTurnActionChosenEvent(seat, OtariaTurnAction.PREPARE),
            OtariaPreparedEvent(seat, listOf(card)),
        )), PassPriority(seat))
        val turn = telemetry.finish().single()
        turn.turnNumber shouldBe 7
        turn.activePlayerId shouldBe seat
        turn.chosenActions.single().action shouldBe OtariaTurnAction.PREPARE
        val change = turn.steps.single().changes.single()
        change.before.librarySize shouldBe 0
        change.after.librarySize shouldBe 1
        change.before.reserveCount shouldBe 1
        change.after.reserveCount shouldBe 0
        change.after.position shouldBe OtariaPosition(8, 8)
        change.after.treasureTokens shouldBe mapOf(OtariaTreasureToken.ROCK to 1)
        turn.steps.single().decisions.single().type shouldBe "EMERGENCY_DECK_OUT_PREPARATION"
    }

    test("turn transitions retain their before-after boundary and completed winner event") {
        val telemetry = OtariaTelemetry()
        telemetry.record(before, ExecutionResult.success(before.copy(turnNumber = 8)), PassPriority(seat))
        telemetry.record(before.copy(turnNumber = 8), ExecutionResult.success(
            before.copy(turnNumber = 8, gameOver = true),
            listOf(GameEndedEvent(seat, GameEndReason.ALTERNATIVE_WIN)),
        ), PassPriority(seat))
        telemetry.finish().map { it.turnNumber } shouldBe listOf(7, 8)
        telemetry.finish().first().steps.single().nextTurnNumber shouldBe 8
        telemetry.end?.winnerId shouldBe seat
    }
})
