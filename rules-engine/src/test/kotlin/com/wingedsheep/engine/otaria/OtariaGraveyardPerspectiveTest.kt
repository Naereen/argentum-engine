package com.wingedsheep.engine.otaria

import com.wingedsheep.engine.core.CardEntityFactory
import com.wingedsheep.engine.core.EngineServices
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.effects.library.GatherCardsExecutor
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.PlayerComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.conditions.Compare
import com.wingedsheep.sdk.scripting.conditions.ComparisonOperator
import com.wingedsheep.sdk.scripting.effects.CardSource
import com.wingedsheep.sdk.scripting.effects.GatherCardsEffect
import com.wingedsheep.sdk.scripting.references.Player
import com.wingedsheep.sdk.scripting.values.Aggregation
import com.wingedsheep.sdk.scripting.values.DynamicAmount
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class OtariaGraveyardPerspectiveTest : FunSpec({
    val explorer = EntityId("explorer")
    val manager = EntityId("manager")
    val encounteredCreature = card("Encounter Threshold Creature") {
        typeLine = "Creature — Human"
        power = 2
        toughness = 2
    }

    fun gameWithEncounter(): Pair<GameState, EntityId> {
        var state = GameState.initial(listOf(explorer, manager))
        val (actorId, afterActor) = state.newEntity()
        state = afterActor.withEntity(
            actorId,
            ComponentContainer.of(PlayerComponent("Encounter"), ManaPoolComponent()),
        )

        fun addGraveyardCards(ownerId: EntityId, count: Int) {
            repeat(count) {
                val (cardId, nextState) = state.newEntity()
                state = nextState
                    .withEntity(cardId, CardEntityFactory.create(encounteredCreature, ownerId))
                    .addToZone(ZoneKey(ownerId, Zone.GRAVEYARD), cardId)
            }
        }

        addGraveyardCards(explorer, 7)
        addGraveyardCards(manager, 2)
        addGraveyardCards(actorId, 1)

        val location = OtariaLocation.entries.first()
        val otaria = OtariaState(
            players = mapOf(
                explorer to OtariaPlayerState(location, location.position),
                manager to OtariaPlayerState(location, location.position),
            ),
            explorationsUsed = OtariaLocation.entries.associateWith { 0 },
            starPile = OtariaExplorationPile(emptyList()),
            colorPiles = Color.entries.associateWith { OtariaExplorationPile(emptyList()) },
            treasurePile = emptyList(),
            locationPile = emptyList(),
            encounters = mapOf(
                actorId to OtariaEncounter(
                    explorerId = explorer,
                    managerId = manager,
                    position = location.position,
                    source = OtariaExplorationSource.Terrain(Color.GREEN),
                    primaryId = actorId,
                ),
            ),
        )
        return state.copy(otaria = otaria) to actorId
    }

    test("encounter threshold reads the explorer's graveyard, not the manager's or encounter's") {
        val registry = CardRegistry()
        registry.register(encounteredCreature)
        val services = EngineServices(registry)
        val (state, actorId) = gameWithEncounter()
        val context = EffectContext(sourceId = actorId, controllerId = actorId)
        val graveyardCount = DynamicAmount.Count(
            Player.You,
            Zone.GRAVEYARD,
            GameObjectFilter.Any.ownedByYou(),
        )

        services.predicateEvaluator.amounts.evaluate(state, graveyardCount, context) shouldBe 7
        services.predicateEvaluator.conditions.evaluate(
            state,
            Compare(graveyardCount, ComparisonOperator.GTE, DynamicAmount.Fixed(7)),
            context,
        ) shouldBe true
    }

    test("encounter graveyard aggregates use the explorer's graveyard too") {
        val registry = CardRegistry()
        registry.register(encounteredCreature)
        val services = EngineServices(registry)
        val (state, actorId) = gameWithEncounter()
        val amount = DynamicAmount.AggregateZone(
            Player.You,
            Zone.GRAVEYARD,
            GameObjectFilter.Creature.ownedByYou(),
            Aggregation.COUNT,
        )

        services.predicateEvaluator.amounts.evaluate(
            state,
            amount,
            EffectContext(sourceId = actorId, controllerId = actorId),
        ) shouldBe 7
    }

    test("gathering from your graveyard uses the explorer's graveyard") {
        val registry = CardRegistry()
        registry.register(encounteredCreature)
        val services = EngineServices(registry)
        val (state, actorId) = gameWithEncounter()
        val gathered = GatherCardsExecutor(services.predicateEvaluator).execute(
            state,
            GatherCardsEffect(
                CardSource.FromZone(Zone.GRAVEYARD, Player.You, GameObjectFilter.Any.ownedByYou()),
                storeAs = "graveyard",
            ),
            EffectContext(sourceId = actorId, controllerId = actorId),
        ).updatedCollections.getValue("graveyard")

        gathered.size shouldBe 7
        gathered.all { state.getEntity(it)?.get<CardComponent>()?.ownerId == explorer } shouldBe true
    }
})
