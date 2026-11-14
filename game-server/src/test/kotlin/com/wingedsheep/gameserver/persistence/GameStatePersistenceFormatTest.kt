package com.wingedsheep.gameserver.persistence

import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.LeylineDecisionContinuation
import com.wingedsheep.engine.core.LeylinePhaseContinuation
import com.wingedsheep.engine.core.PendingDecision
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.core.suspendForDecision
import com.wingedsheep.engine.otaria.OtariaCardKind
import com.wingedsheep.engine.otaria.OtariaExplorationPile
import com.wingedsheep.engine.otaria.OtariaExplorationSource
import com.wingedsheep.engine.otaria.OtariaExplorationStage
import com.wingedsheep.engine.otaria.OtariaLocation
import com.wingedsheep.engine.otaria.OtariaPendingExploration
import com.wingedsheep.engine.otaria.OtariaPileCard
import com.wingedsheep.engine.otaria.OtariaPlayerState
import com.wingedsheep.engine.otaria.OtariaState
import com.wingedsheep.engine.otaria.OtariaTreasure
import com.wingedsheep.engine.otaria.OtariaTreasureToken
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.LibraryPositionAnchor
import com.wingedsheep.engine.state.components.identity.RevealedToComponent
import com.wingedsheep.engine.view.ClientEvent
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class GameStatePersistenceFormatTest : FunSpec({
    val player = EntityId("player")
    val question: PendingDecision = YesNoDecision(
        id = "old-question", playerId = player, prompt = "Begin with this leyline?", context = DecisionContext(),
    )

    test("persistence JSON roundtrips structured Otaria initiative notices") {
        val notice = ClientEvent.OtariaNotice(
            description = "The explorer won initiative (5 vs 3): fight or flee freely.",
            initiativeOutcome = "explorerWon",
            explorerId = player,
            initiativeRolls = "5 vs 3",
        )
        val serializer = ListSerializer(ClientEvent.serializer())
        val encoded = persistenceJson.encodeToString(serializer, listOf(notice))
        persistenceJson.decodeFromString(serializer, encoded).single() shouldBe notice
    }

    test("persistence JSON roundtrips current execution and permits unrelated unknown fields") {
        val current = GameState(nextRoutingId = 23).pushContinuation(LeylinePhaseContinuation).suspendForDecision(
            question = { id -> (question as YesNoDecision).copy(id = id) },
            answer = LeylineDecisionContinuation(player, EntityId("leyline"), "Test leyline"),
        ).state
        val encoded = persistenceJson.parseToJsonElement(persistenceJson.encodeToString(current)).jsonObject
        val extended = JsonObject(encoded + ("unrelatedMetadata" to JsonPrimitive("ignored")))
        val restored = persistenceJson.decodeFromString<GameState>(extended.toString())
        restored shouldBe current
        restored.nextRoutingId shouldBe 24L
        restored.pendingDecision shouldBe current.pendingDecision
    }

    test("persistence preserves private Otaria state and either pending exploration source") {
        val location = OtariaLocation.KROSIAN_FOREST
        val discovery = OtariaPileCard("Discovery", OtariaCardKind.DISCOVERY)
        val reserve = EntityId("private-card")
        val knownCard = EntityId("known-library-card")
        val otherViewer = EntityId("other-viewer")
        val knowledge = RevealedToComponent(
            playerIds = setOf(player, otherViewer),
            libraryPositionAnchors = mapOf(
                player to LibraryPositionAnchor.BOTH,
                otherViewer to LibraryPositionAnchor.BOTTOM,
            ),
        )
        val base = OtariaState(
            players = mapOf(
                player to OtariaPlayerState(
                    location, location.position, locationCards = listOf(location), reserve = listOf(reserve),
                    treasureTokens = mapOf(OtariaTreasureToken.SCRAP to 2),
                ),
            ),
            explorationsUsed = OtariaLocation.entries.associateWith { if (it == location) 1 else 0 },
            starPile = OtariaExplorationPile(emptyList(), listOf(discovery)),
            colorPiles = mapOf(Color.GREEN to OtariaExplorationPile(listOf(discovery))),
            treasurePile = listOf(OtariaTreasure.Card("Mirari"), OtariaTreasure.Token(OtariaTreasureToken.ROCK)),
            locationPile = listOf(location),
        )
        for (source in listOf(OtariaExplorationSource.Star(location), OtariaExplorationSource.Terrain(Color.GREEN))) {
            val current = GameState(
                entities = mapOf(knownCard to ComponentContainer.of(knowledge)),
                zones = mapOf(ZoneKey(player, Zone.LIBRARY) to listOf(knownCard)),
                otaria = base.copy(
                    pendingExploration = OtariaPendingExploration(
                        player, EntityId("manager"), source, discovery, OtariaExplorationStage.CHOOSING_LOCATION,
                    ),
                ),
            )
            val restored = persistenceJson.decodeFromString<GameState>(persistenceJson.encodeToString(current))
            restored shouldBe current
            restored.getEntity(knownCard)?.get<RevealedToComponent>() shouldBe knowledge
        }
    }

    test("generic persistence decoding cannot silently lose an old question without an answer frame") {
        // Keep the actual generic persistence path: this is invalid even for a future legacy reader,
        // because the valid question has no represented operation that can consume its answer.
        val encoded = persistenceJson.parseToJsonElement(persistenceJson.encodeToString(GameState())).jsonObject
        val old = JsonObject(encoded + (
            "pendingDecision" to persistenceJson.parseToJsonElement(persistenceJson.encodeToString(question))
        ))
        shouldThrow<SerializationException> {
            persistenceJson.decodeFromString<GameState>(old.toString())
        }
    }
})
