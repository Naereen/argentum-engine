package com.wingedsheep.ai.engine

import com.wingedsheep.ai.ActionResponse
import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.otaria.*
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.LifeTotalComponent
import com.wingedsheep.engine.state.components.identity.PlayerComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.view.ClientStateTransformer
import com.wingedsheep.engine.view.LegalActionInfo
import com.wingedsheep.sdk.core.*
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class OtariaEncounterAiTest : FunSpec({
    val explorer = EntityId("explorer")
    val manager = EntityId("manager")
    val other = EntityId("other")
    val actor = EntityId("encounter")
    val primary = EntityId("primary")
    val forest = CardDefinition.basicLand("Forest", Subtype.FOREST)
    val creature = CardDefinition.creature("Encounter", ManaCost.ZERO, emptySet(), 3, 3)
    val registry = CardRegistry().also { listOf(forest, creature).forEach(it::register) }
    val services = EngineServices(registry)
    val transformer = ClientStateTransformer(registry, predicateEvaluator = services.predicateEvaluator)
    val players = listOf(explorer, manager, other)

    fun game(): GameState {
        val mode = OtariaState(
            players = players.associateWith {
                OtariaPlayerState(OtariaLocation.KROSIAN_FOREST, OtariaLocation.KROSIAN_FOREST.position)
            },
            explorationsUsed = OtariaLocation.entries.associateWith { 0 },
            starPile = OtariaExplorationPile(emptyList()),
            colorPiles = Color.entries.associateWith { OtariaExplorationPile(emptyList()) },
            treasurePile = emptyList(),
            locationPile = emptyList(),
        )
        val state = GameState(
            entities = (players + actor).associateWith {
                ComponentContainer.of(PlayerComponent(it.value, 15), LifeTotalComponent(15), ManaPoolComponent())
            },
            turnOrder = players,
            activePlayerId = explorer,
            priorityPlayerId = explorer,
            step = Step.OTARIA_EXPLORATION,
            phase = Phase.OTARIA_EXPLORATION,
            turnNumber = 1,
            otaria = mode,
        ).withEntity(primary, CardEntityFactory.create(creature, actor))
            .addToZone(ZoneKey(actor, Zone.BATTLEFIELD), primary)
            .withEntity(EntityId("land"), CardEntityFactory.create(forest, explorer))
            .addToZone(ZoneKey(explorer, Zone.BATTLEFIELD), EntityId("land"))
        return state.copy(otaria = mode.copy(encounters = mapOf(actor to OtariaEncounter(
            explorer, manager, OtariaLocation.KROSIAN_FOREST.position,
            OtariaExplorationSource.Terrain(Color.GREEN), primary,
            members = setOf(requireNotNull(state.objectRef(primary))),
        ))))
    }

    test("an AI manager answers an encounter's entry mana decision under the encounter actor") {
        val before = game()
        val asked = before.suspendForDecision(
            question = { id ->
                ChooseColorDecision(id, actor, "Choose encounter mana", DecisionContext())
            },
            answer = OtariaEncounterEntryManaChoiceContinuation(actor, requireNotNull(before.objectRef(primary)), 1),
        ).state
        val controller = EngineAiPlayerController(registry, manager, { asked }, profile = AiProfile.LEGACY_V0)
        val response = controller.chooseAction(transformer.transform(asked, manager), emptyList(), asked.pendingDecision)
            as ActionResponse.SubmitDecision
        response.playerId shouldBe actor
        ActionProcessor(services).process(asked, SubmitDecision(response.playerId, response.response)).result.error shouldBe null
    }

    test("the managing AI declares attacks for the encounter rather than for its own army") {
        val state = game().copy(
            activePlayerId = actor, priorityPlayerId = actor,
            step = Step.DECLARE_ATTACKERS, phase = Phase.COMBAT, turnNumber = 2,
        )
        val controller = EngineAiPlayerController(registry, manager, { state }, profile = AiProfile.LEGACY_V0)
        val offered = LegalActionInfo(
            "DeclareAttackers", "Declare attackers", DeclareAttackers(actor, emptyMap()),
            validAttackers = listOf(primary), validAttackTargets = listOf(explorer),
        )
        val response = controller.chooseAction(transformer.transform(state, manager), listOf(offered), null)
            as ActionResponse.SubmitAction
        response.action.playerId shouldBe actor
        val declaration = response.action as DeclareAttackers
        declaration.attackers shouldBe mapOf(primary to explorer)
        ActionProcessor(services).process(state, declaration).result.error shouldBe null
    }

    test("an explorer AI with no army chooses free flight instead of an empty combat") {
        val state = game()
        val decision = ChooseOptionDecision(
            "initiative", explorer, "You won initiative: fight or flee?", DecisionContext(),
            options = listOf("Fight the encounter", "Flee without a contest"),
            optionMetadata = listOf(
                OptionMetadata(id = "otaria-initiative:combat"),
                OptionMetadata(id = "otaria-initiative:move"),
            ),
        )
        val response = AIPlayer.create(registry, explorer).respondToDecision(state, decision) as OptionChosenResponse
        response.optionIndex shouldBe 1
    }
})
