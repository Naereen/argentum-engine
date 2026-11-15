package com.wingedsheep.gameserver.ai

import com.wingedsheep.ai.ActionResponse
import com.wingedsheep.ai.AiPlayerController
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.ChooseColorDecision
import com.wingedsheep.engine.core.ColorChosenResponse
import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.view.ClientGameState
import com.wingedsheep.engine.view.ClientOtariaEncounter
import com.wingedsheep.engine.view.ClientOtariaPile
import com.wingedsheep.engine.view.ClientOtariaState
import com.wingedsheep.engine.view.LegalActionInfo
import com.wingedsheep.engine.view.StateDelta
import com.wingedsheep.gameserver.protocol.ServerMessage
import com.wingedsheep.engine.otaria.OtariaPosition
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class AiWebSocketSessionFallbackTest : FunSpec({
    val player = EntityId.of("external-ai")
    val pass = PassPriority(player)
    val actions = listOf(LegalActionInfo("PassPriority", "Pass priority", pass))
    val state = ClientGameState(
        viewingPlayerId = player,
        cards = emptyMap(),
        zones = emptyList(),
        players = emptyList(),
        currentPhase = Phase.PRECOMBAT_MAIN,
        currentStep = Step.PRECOMBAT_MAIN,
        activePlayerId = player,
        priorityPlayerId = player,
        turnNumber = 1,
        isGameOver = false,
        winnerId = null,
        combat = null,
    )

    test("external delta without a baseline cannot submit a heuristic and a full update restores controller use") {
        val controller = mockk<AiPlayerController>()
        every { controller.chooseAction(any(), any(), any(), any()) } returns ActionResponse.SubmitAction(pass)
        val submitted = mutableListOf<Pair<GameAction, String?>>()
        val socket = AiWebSocketSession(
            aiPlayerId = player,
            controller = controller,
            thinkingDelayMs = 0,
            onActionReady = { _, action, epoch -> submitted.add(action to epoch) },
            onMulliganKeep = {},
            onMulliganTake = {},
            onBottomCards = { _, _ -> },
            allowActionsOnlyFallback = false,
        )
        try {
            shouldThrow<IllegalStateException> {
                socket.handleServerMessage(ServerMessage.StateDeltaUpdate(
                    delta = StateDelta(players = emptyList(), turnNumber = 2),
                    events = emptyList(),
                    legalActions = actions,
                    interactionEpoch = "missing-baseline",
                ))
            }
            submitted shouldBe emptyList()
            verify(exactly = 0) { controller.chooseAction(any(), any(), any(), any()) }

            socket.handleServerMessage(ServerMessage.StateUpdate(
                state = state,
                events = emptyList(),
                legalActions = actions,
                interactionEpoch = "full",
            ))
            socket.handleServerMessage(ServerMessage.StateDeltaUpdate(
                delta = StateDelta(players = emptyList(), turnNumber = 3),
                events = emptyList(),
                legalActions = actions,
                interactionEpoch = "delta",
            ))

            submitted shouldBe listOf(pass to "full", pass to "delta")
            verify(exactly = 1) { controller.chooseAction(state, actions, null, emptyList()) }
            verify(exactly = 1) {
                controller.chooseAction(state.copy(turnNumber = 3), actions, null, emptyList())
            }
        } finally {
            socket.close()
        }
    }

    test("Otaria AI passes an opponent window immediately when no meaningful response is available") {
        val opponent = EntityId.of("opponent")
        val otaria = ClientOtariaState(
            tiles = emptyList(),
            players = emptyList(),
            locations = emptyList(),
            starPile = ClientOtariaPile(0, 0),
            colorPiles = emptyMap(),
            treasureCount = 0,
            locationCount = 0,
        )
        val opponentState = state.copy(activePlayerId = opponent, otaria = otaria)
        val controller = mockk<AiPlayerController>()
        val submitted = mutableListOf<Pair<GameAction, String?>>()
        val socket = AiWebSocketSession(
            aiPlayerId = player,
            controller = controller,
            thinkingDelayMs = 10_000,
            onActionReady = { _, action, epoch -> submitted.add(action to epoch) },
            onMulliganKeep = {},
            onMulliganTake = {},
            onBottomCards = { _, _ -> },
        )
        try {
            socket.handleServerMessage(ServerMessage.StateUpdate(
                state = opponentState,
                events = emptyList(),
                legalActions = actions,
                interactionEpoch = "otaria-opponent-window",
            ))

            submitted shouldBe listOf(pass to "otaria-opponent-window")
            verify(exactly = 0) { controller.chooseAction(any(), any(), any(), any()) }
        } finally {
            socket.close()
        }
    }

    test("an AI managing an Otaria encounter still handles that encounter's turn") {
        val actor = EntityId.of("encounter")
        val otaria = ClientOtariaState(
            tiles = emptyList(),
            players = emptyList(),
            locations = emptyList(),
            starPile = ClientOtariaPile(0, 0),
            colorPiles = emptyMap(),
            treasureCount = 0,
            locationCount = 0,
            encounters = listOf(ClientOtariaEncounter(
                actorId = actor,
                explorerId = EntityId.of("explorer"),
                managerId = player,
                position = OtariaPosition(6, 5),
                participants = emptySet(),
            )),
        )
        val encounterState = state.copy(activePlayerId = actor, otaria = otaria)
        val controller = mockk<AiPlayerController>()
        every {
            controller.chooseAction(encounterState, actions, null, emptyList())
        } returns ActionResponse.SubmitAction(pass)
        val submitted = mutableListOf<Pair<GameAction, String?>>()
        val socket = AiWebSocketSession(
            aiPlayerId = player,
            controller = controller,
            thinkingDelayMs = 0,
            onActionReady = { _, action, epoch -> submitted.add(action to epoch) },
            onMulliganKeep = {},
            onMulliganTake = {},
            onBottomCards = { _, _ -> },
        )
        try {
            socket.handleServerMessage(ServerMessage.StateUpdate(
                state = encounterState,
                events = emptyList(),
                legalActions = actions,
                interactionEpoch = "otaria-managed-encounter",
            ))

            submitted shouldBe listOf(pass to "otaria-managed-encounter")
            verify(exactly = 1) { controller.chooseAction(encounterState, actions, null, emptyList()) }
        } finally {
            socket.close()
        }
    }

    test("an AI still considers meaningful Otaria responses during an opponent's turn") {
        val opponent = EntityId.of("opponent")
        val otaria = ClientOtariaState(
            tiles = emptyList(),
            players = emptyList(),
            locations = emptyList(),
            starPile = ClientOtariaPile(0, 0),
            colorPiles = emptyMap(),
            treasureCount = 0,
            locationCount = 0,
        )
        val opponentState = state.copy(activePlayerId = opponent, otaria = otaria)
        val cast = CastSpell(playerId = player, cardId = EntityId.of("instant"))
        val responseActions = actions + LegalActionInfo("CastSpell", "Cast instant", cast)
        val controller = mockk<AiPlayerController>()
        every {
            controller.chooseAction(opponentState, responseActions, null, emptyList())
        } returns ActionResponse.SubmitAction(pass)
        val socket = AiWebSocketSession(
            aiPlayerId = player,
            controller = controller,
            thinkingDelayMs = 0,
            onActionReady = { _, _, _ -> },
            onMulliganKeep = {},
            onMulliganTake = {},
            onBottomCards = { _, _ -> },
        )
        try {
            socket.handleServerMessage(ServerMessage.StateUpdate(
                state = opponentState,
                events = emptyList(),
                legalActions = responseActions,
                interactionEpoch = "otaria-response-window",
            ))

            verify(exactly = 1) { controller.chooseAction(opponentState, responseActions, null, emptyList()) }
        } finally {
            socket.close()
        }
    }

    test("built-in seats retain actions-only fallback with the original interaction epoch") {
        val controller = mockk<AiPlayerController>()
        val submitted = mutableListOf<Pair<GameAction, String?>>()
        val socket = AiWebSocketSession(
            aiPlayerId = player,
            controller = controller,
            thinkingDelayMs = 0,
            onActionReady = { _, action, epoch -> submitted.add(action to epoch) },
            onMulliganKeep = {},
            onMulliganTake = {},
            onBottomCards = { _, _ -> },
            allowActionsOnlyFallback = true,
        )
        try {
            socket.handleServerMessage(ServerMessage.StateDeltaUpdate(
                delta = StateDelta(players = emptyList()),
                events = emptyList(),
                legalActions = actions,
                interactionEpoch = "fallback-origin",
            ))

            submitted shouldBe listOf(pass to "fallback-origin")
            verify(exactly = 0) { controller.chooseAction(any(), any(), any(), any()) }
        } finally {
            socket.close()
        }
    }

    test("an authorized encounter decision is answered under its actor even with no legal actions") {
        val actor = EntityId.of("encounter")
        val decision = ChooseColorDecision("mana", actor, "Choose encounter mana", DecisionContext())
        val response = ColorChosenResponse("mana", Color.GREEN)
        val controller = mockk<AiPlayerController>()
        every { controller.chooseAction(state, emptyList(), decision, emptyList()) } returns
            ActionResponse.SubmitDecision(actor, response)
        val submitted = mutableListOf<Pair<GameAction, String?>>()
        val socket = AiWebSocketSession(
            aiPlayerId = player,
            controller = controller,
            thinkingDelayMs = 0,
            onActionReady = { _, action, epoch -> submitted.add(action to epoch) },
            onMulliganKeep = {},
            onMulliganTake = {},
            onBottomCards = { _, _ -> },
        )
        try {
            socket.handleServerMessage(ServerMessage.StateUpdate(
                state = state, events = emptyList(), legalActions = emptyList(),
                pendingDecision = decision, interactionEpoch = "encounter-entry",
            ))
            submitted shouldBe listOf(SubmitDecision(actor, response) to "encounter-entry")
            verify(exactly = 1) { controller.chooseAction(state, emptyList(), decision, emptyList()) }
        } finally {
            socket.close()
        }
    }

    test("actions-only fallback also answers an encounter decision using its actor") {
        val actor = EntityId.of("encounter")
        val decision = SelectCardsDecision(
            "cards", actor, "Choose encounter cards", DecisionContext(),
            options = emptyList(), minSelections = 0, maxSelections = 0,
        )
        val submitted = mutableListOf<GameAction>()
        val socket = AiWebSocketSession(
            aiPlayerId = player,
            controller = mockk(),
            thinkingDelayMs = 0,
            onActionReady = { _, action, _ -> submitted.add(action) },
            onMulliganKeep = {},
            onMulliganTake = {},
            onBottomCards = { _, _ -> },
            allowActionsOnlyFallback = true,
        )
        try {
            socket.handleServerMessage(ServerMessage.StateDeltaUpdate(
                delta = StateDelta(players = emptyList()), events = emptyList(), legalActions = emptyList(),
                pendingDecision = decision, interactionEpoch = "encounter-fallback",
            ))
            submitted.size shouldBe 1
            (submitted.single() as SubmitDecision).playerId shouldBe actor
        } finally {
            socket.close()
        }
    }
})
