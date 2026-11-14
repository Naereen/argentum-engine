package com.wingedsheep.gameserver.otaria

import com.wingedsheep.gameserver.GameServerTestBase
import com.wingedsheep.gameserver.ai.AiGameManager
import com.wingedsheep.gameserver.ai.AiWebSocketSession
import com.wingedsheep.gameserver.protocol.ClientMessage
import com.wingedsheep.gameserver.protocol.ServerMessage
import com.wingedsheep.gameserver.repository.GameRepository
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.time.Duration.Companion.seconds

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "game.ai.enabled=true",
        "game.ai.mode=engine",
    ],
)
class OtariaAiControlTest(
    @param:Autowired private val playtest: OtariaPlaytestService,
    @param:Autowired private val games: GameRepository,
    @param:Autowired private val ai: AiGameManager,
) : GameServerTestBase() {
    private fun latestAiControlStatus(client: TestWebSocketClient): Boolean? =
        client.messages.lastOrNull { message ->
            message is ServerMessage.StateUpdate || message is ServerMessage.StateDeltaUpdate
        }?.let { message ->
            when (message) {
                is ServerMessage.StateUpdate -> message.aiControlEnabled
                is ServerMessage.StateDeltaUpdate -> message.aiControlEnabled
                else -> null
            }
        }

    init {
        test("a human can hand an Otaria seat to the AI and reclaim it without losing their session") {
            val scenario = playtest.start(
                OtariaPlaytestRequest(playerName = "Takeover explorer", aiOpponents = 2, seed = 41),
            )
            val playerId = EntityId(scenario.player1.playerId)
            val game = games.findById(scenario.sessionId).shouldNotBeNull()
            try {
                val client = createClient()
                client.reconnectAs(scenario.player1.name, scenario.player1.token) shouldBe playerId.value
                eventually(10.seconds) {
                    client.latestState().shouldNotBeNull().otaria.shouldNotBeNull()
                }

                client.send(ClientMessage.SetAiControl(enabled = true))
                eventually(10.seconds) {
                    latestAiControlStatus(client) shouldBe true
                }
                ai.isHumanTakeover(scenario.sessionId, playerId) shouldBe true
                ai.humanTakeoverSession(scenario.sessionId, playerId).shouldNotBeNull()
                (game.getPlayerSession(playerId)?.webSocketSession is AiWebSocketSession) shouldBe false
                ai.isAiPlayer(playerId) shouldBe false

                client.send(ClientMessage.KeepHand)
                eventually(5.seconds) {
                    client.latestError()?.message shouldBe
                        "The AI is playing your seat. Take back control first."
                }

                val stateUpdatesAfterEnable = client.stateUpdateCount()
                eventually(20.seconds) {
                    withClue("the server should keep sending this seat's state while the AI drives it") {
                        (client.stateUpdateCount() > stateUpdatesAfterEnable) shouldBe true
                    }
                }

                client.send(ClientMessage.SetAiControl(enabled = false))
                eventually(10.seconds) {
                    latestAiControlStatus(client) shouldBe false
                }
                ai.isHumanTakeover(scenario.sessionId, playerId) shouldBe false
                (game.getPlayerSession(playerId)?.webSocketSession is AiWebSocketSession) shouldBe false
            } finally {
                ai.cleanupGame(scenario.sessionId)
            }
        }
    }
}
