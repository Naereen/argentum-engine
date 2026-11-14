package com.wingedsheep.ai.engine

import com.wingedsheep.ai.engine.rollout.PlayoutPolicy
import com.wingedsheep.engine.core.ActionProcessor
import com.wingedsheep.engine.core.CardEntityFactory
import com.wingedsheep.engine.core.ClaimOtariaMirariVictory
import com.wingedsheep.engine.core.PlayLand
import com.wingedsheep.engine.otaria.OtariaExplorationPile
import com.wingedsheep.engine.otaria.OtariaLocation
import com.wingedsheep.engine.otaria.OtariaPlayerState
import com.wingedsheep.engine.otaria.OtariaState
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.identity.OwnerComponent
import com.wingedsheep.engine.state.components.identity.PlayerComponent
import com.wingedsheep.engine.state.components.player.LandDropsComponent
import com.wingedsheep.engine.state.components.stack.SpellOnStackComponent
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.model.GameRng
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class OtariaVictoryAiTest : FunSpec({
    val p1 = EntityId("player-1")
    val p2 = EntityId("player-2")
    val p3 = EntityId("player-3")
    val players = listOf(p1, p2, p3)
    val mirari = EntityId("mirari")
    val land = EntityId("land")
    val spell = EntityId("spell")
    val landDefinition = card("Otaria Test Land") { typeLine = "Land" }
    val registry = CardRegistry().apply { register(landDefinition) }
    val simulator = GameSimulator(registry)

    fun eligible(claimant: EntityId): GameState {
        val location = OtariaLocation.KROSIAN_FOREST
        val mode = OtariaState(
            players = players.associateWith {
                OtariaPlayerState(
                    startingLocation = location,
                    position = location.position,
                    locationCards = if (it == claimant) listOf(location) else emptyList(),
                    reserve = if (it == claimant) listOf(mirari) else emptyList(),
                )
            },
            explorationsUsed = OtariaLocation.entries.associateWith { 0 },
            starPile = OtariaExplorationPile(emptyList()),
            colorPiles = Color.entries.associateWith { OtariaExplorationPile(emptyList()) },
            treasurePile = emptyList(),
            locationPile = emptyList(),
        )
        val card = CardComponent(
            cardDefinitionId = "Mirari",
            name = "Mirari",
            manaCost = ManaCost(emptyList()),
            typeLine = TypeLine(cardTypes = setOf(CardType.ARTIFACT)),
            ownerId = claimant,
        )
        return GameState(
            entities = players.associateWith {
                ComponentContainer.of(PlayerComponent(it.value), LandDropsComponent())
            },
            turnOrder = players,
            activePlayerId = p1,
            priorityPlayerId = claimant,
            step = Step.UPKEEP,
            otaria = mode,
        ).withEntity(mirari, ComponentContainer.of(card, OwnerComponent(claimant)))
    }

    test("AI claims victory on an opponent's turn with a spell on the stack") {
        val state = eligible(p2).withEntity(spell, ComponentContainer.of(
            ControllerComponent(p1), SpellOnStackComponent(p1),
        )).copy(stack = listOf(spell))
        val actions = simulator.getLegalActions(state, p2)
        actions.any { it.action is ClaimOtariaMirariVictory } shouldBe true
        val ai = AIPlayer.create(registry, p2)
        val action = ai.chooseAction(state)
        action shouldBe ClaimOtariaMirariVictory(p2, mirari)
        val result = ActionProcessor(registry).process(state, action).result
        result.error shouldBe null
        result.state.winnerId shouldBe p2
    }

    test("AI's action-free auto-pass shortcut does not skip an available claim") {
        val state = eligible(p2).copy(step = Step.BEGIN_COMBAT, phase = Step.BEGIN_COMBAT.phase)
        val profile = AiProfile.CURRENT.copy(useMeaningfulFilter = true)
        val ai = AIPlayer.create(registry, p2, profile)
        ai.chooseAction(state) shouldBe ClaimOtariaMirariVictory(p2, mirari)
    }

    test("playout takes the immediate win before a land drop for every random seed") {
        val state = eligible(p1).copy(step = Step.PRECOMBAT_MAIN, phase = Step.PRECOMBAT_MAIN.phase)
            .withEntity(land, CardEntityFactory.create(landDefinition, p1))
            .addToZone(ZoneKey(p1, Zone.HAND), land)
        val actions = simulator.getLegalActions(state, p1)
        actions.any { it.action is PlayLand } shouldBe true
        actions.any { it.action is ClaimOtariaMirariVictory } shouldBe true
        AIPlayer.create(registry, p1).chooseAction(state) shouldBe ClaimOtariaMirariVictory(p1, mirari)
        val evaluator = AIPlayer.defaultEvaluator()
        val policy = PlayoutPolicy(CombatAdvisor(simulator, evaluator, registry), cardRegistry = registry)
        for (seed in 0L..31L) {
            val rng = GameRng.seeded(seed)
            val (choice, nextRng) = policy.decide(state, p1, rng) { actions }
            choice shouldBe ClaimOtariaMirariVictory(p1, mirari)
            nextRng shouldBe rng
        }
    }
})
