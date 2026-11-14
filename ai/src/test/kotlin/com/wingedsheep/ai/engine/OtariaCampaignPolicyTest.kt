package com.wingedsheep.ai.engine

import com.wingedsheep.ai.engine.rollout.FastDecisionResponder
import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.otaria.*
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.TappedComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.identity.LifeTotalComponent
import com.wingedsheep.engine.state.components.identity.PlayerComponent
import com.wingedsheep.engine.state.components.identity.TokenComponent
import com.wingedsheep.engine.state.components.player.LandDropsComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.sdk.core.*
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class OtariaCampaignPolicyTest : FunSpec({
    val seats = (1..3).map { EntityId("player-$it") }
    val bot = seats.first()
    val forest = CardDefinition.basicLand("Forest", Subtype.FOREST)
    val bear = CardDefinition.creature("Campaign Bear", ManaCost.parse("{1}{G}"), emptySet(), 2, 2)
    val expensive = CardDefinition.creature("Campaign Giant", ManaCost.parse("{7}{U}"), emptySet(), 8, 8)
    val defender = CardDefinition.creature(
        "Campaign Flyer", ManaCost.parse("{4}{G}"), emptySet(), 4, 6, keywords = setOf(Keyword.REACH),
    )
    val fighter = CardDefinition.creature("Campaign Fighter", ManaCost.parse("{3}{G}"), emptySet(), 4, 5)
    val mirari = card("Mirari") { typeLine = "Legendary Artifact"; manaCost = "{5}" }
    val registry = CardRegistry().apply { listOf(forest, bear, expensive, mirari, defender, fighter).forEach(::register) }
    val services = EngineServices(registry)
    val processor = ActionProcessor(services)
    val policy = OtariaCampaignPolicy()
    val mirariId = EntityId("mirari")
    val bearId = EntityId("bear")
    val forestId = EntityId("forest")
    val giantId = EntityId("giant")

    fun game(size: Int = 10, playerCount: Int = 3): GameState {
        val players = (1..playerCount).map { EntityId("player-$it") }
        val state = GameState(
            entities = players.associateWith {
                ComponentContainer.of(PlayerComponent(it.value, 15), LifeTotalComponent(15),
                    ManaPoolComponent(), LandDropsComponent())
            },
            turnOrder = players,
            activePlayerId = bot,
            priorityPlayerId = bot,
            turnNumber = 1,
            step = Step.UPKEEP,
            phase = Step.UPKEEP.phase,
            otaria = OtariaState(
                players = players.zip(OtariaLocation.entries).associate { (id, location) ->
                    id to OtariaPlayerState(location, location.position)
                },
                explorationsUsed = OtariaLocation.entries.associateWith { 0 },
                starPile = OtariaExplorationPile(listOf(OtariaPileCard("Reward", OtariaCardKind.NONCREATURE))),
                colorPiles = Color.entries.associateWith {
                    OtariaExplorationPile(listOf(OtariaPileCard("Reward", OtariaCardKind.NONCREATURE)))
                },
                treasurePile = emptyList(),
                locationPile = OtariaLocation.entries,
            ),
        )
        val library = (0 until size).fold(state) { current, index ->
            val id = EntityId("library-$index")
            current.withEntity(id, CardEntityFactory.create(forest, bot)).addToZone(ZoneKey(bot, Zone.LIBRARY), id)
        }
        return (0 until 5).fold(library) { current, index ->
            val id = EntityId("hand-$index")
            current.withEntity(id, CardEntityFactory.create(forest, bot)).addToZone(ZoneKey(bot, Zone.HAND), id)
        }
    }

    fun player(state: GameState, update: (OtariaPlayerState) -> OtariaPlayerState): GameState {
        val mode = requireNotNull(state.otaria)
        return state.copy(otaria = mode.copy(players = mode.players + (bot to update(mode.players.getValue(bot)))))
    }

    fun reserve(state: GameState, vararg cards: Pair<EntityId, CardDefinition>): GameState {
        val added = cards.fold(state) { current, (id, definition) ->
            current.withEntity(id, CardEntityFactory.create(definition, bot))
        }
        return player(added) { it.copy(reserve = cards.map { pair -> pair.first }) }
    }

    fun battlefield(state: GameState, seat: EntityId, id: EntityId, definition: CardDefinition): GameState =
        state.withEntity(id, CardEntityFactory.create(definition, seat)).addToZone(ZoneKey(seat, Zone.BATTLEFIELD), id)

    fun readyArmy(state: GameState): GameState =
        battlefield(
            battlefield(battlefield(state, bot, EntityId("reach"), defender), bot, EntityId("fighter-1"), fighter),
            bot, EntityId("fighter-2"), fighter,
        )

    fun turn(state: GameState): GameState = state.suspendForDecision(
        question = { ChooseOptionDecision(it, bot, "Localized action prompt", DecisionContext(),
            options = listOf("M", "E", "P", "C")) },
        answer = OtariaTurnChoiceContinuation(bot),
    ).state

    fun action(state: GameState): OtariaTurnAction {
        val asked = turn(state)
        val decision = requireNotNull(asked.pendingDecision)
        val response = policy.respond(asked, decision) as OptionChosenResponse
        AIPlayer.create(registry, bot).respondToDecision(asked, decision) shouldBe response
        FastDecisionResponder().respond(asked, decision, seats[1]) shouldBe response
        return OtariaTurnAction.entries[response.optionIndex]
    }

    fun movement(state: GameState): Pair<GameState, List<OtariaPosition>> {
        val position = requireNotNull(state.otaria).players.getValue(bot).position
        val range = if (state.projectedState.getBattlefieldControlledBy(bot).size >= 10) 1L else 2L
        val destinations = OtariaBoard.tiles.keys.filter { OtariaBoard.distance(position, it) in 1L..range }
        val asked = state.suspendForDecision(
            question = { ChooseOptionDecision(it, bot, "Destination", DecisionContext(),
                options = destinations.map { p -> p.toString() }) },
            answer = OtariaMovementChoiceContinuation(bot, destinations),
        ).state
        return asked to destinations
    }

    fun destination(state: GameState): OtariaPosition {
        val (asked, destinations) = movement(state)
        val decision = requireNotNull(asked.pendingDecision)
        val response = policy.respond(asked, decision) as OptionChosenResponse
        FastDecisionResponder().respond(asked, decision, bot) shouldBe response
        return destinations[response.optionIndex]
    }

    test("library sizes zero through four force preparation even while carrying Mirari") {
        for (size in 0..4) {
            val state = player(reserve(game(size), mirariId to mirari, bearId to bear)) {
                it.copy(locationCards = listOf(OtariaLocation.BALSHAN_BAY))
            }
            action(state) shouldBe OtariaTurnAction.PREPARE
        }
    }

    test("five cards is not an emergency and an empty reserve never forces preparation") {
        val carrying = player(reserve(game(5), mirariId to mirari)) {
            it.copy(locationCards = listOf(OtariaLocation.BALSHAN_BAY))
        }
        action(carrying) shouldBe OtariaTurnAction.MOVE
        action(game(0)) shouldBe OtariaTurnAction.EXPLORE
    }

    test("the engine's actual turn-start question uses the campaign policy and records its action") {
        val initial = reserve(game(4), bearId to bear)
        val started = services.mulliganHandler.startFirstTurn(initial, emptyList(), services.turnManager).state
        val decision = requireNotNull(started.pendingDecision)
        val response = AIPlayer.create(registry, bot).respondToDecision(started, decision)
        val result = processor.process(started, SubmitDecision(bot, response)).result
        result.error shouldBe null
        result.state.otaria?.turn?.action shouldBe OtariaTurnAction.PREPARE
        result.events.filterIsInstance<OtariaTurnActionChosenEvent>().single().action shouldBe OtariaTurnAction.PREPARE
    }

    test("Mirari follows shortest hex routes to a held location for three through five players") {
        val target = OtariaLocation.CEPHALID_SOUTHERN_PALACE
        for (count in 3..5) {
            var state = player(reserve(game(playerCount = count), mirariId to mirari)) {
                it.copy(locationCards = listOf(target))
            }
            val start = requireNotNull(state.otaria).players.getValue(bot).position
            var moves = 0
            while (requireNotNull(state.otaria).players.getValue(bot).position != target.position) {
                action(state) shouldBe OtariaTurnAction.MOVE
                val before = requireNotNull(state.otaria).players.getValue(bot).position
                val next = destination(state)
                OtariaBoard.distance(next, target.position) shouldBe
                    (OtariaBoard.distance(before, target.position) - 2).coerceAtLeast(0)
                state = player(state) { it.copy(position = next) }
                moves++
                check(moves < 20)
            }
            moves.toLong() shouldBe (OtariaBoard.distance(start, target.position) + 1) / 2
            AIPlayer.create(registry, bot).chooseAction(state) shouldBe ClaimOtariaMirariVictory(bot, mirariId)
        }
    }

    test("Mirari chooses the nearest held star and ignores a nearer unheld star") {
        val nearest = OtariaLocation.ORDER_ENCAMPMENT
        val state = player(reserve(game(), mirariId to mirari)) {
            it.copy(locationCards = listOf(OtariaLocation.CEPHALID_SOUTHERN_PALACE, nearest))
        }
        val next = destination(state)
        OtariaBoard.distance(next, nearest.position) shouldBe 0L
    }

    test("ten permanents reduce Mirari travel to one hex per turn") {
        var state = player(reserve(game(), mirariId to mirari)) {
            it.copy(locationCards = listOf(OtariaLocation.BALSHAN_BAY))
        }
        repeat(10) { state = battlefield(state, bot, EntityId("land-$it"), forest) }
        val before = requireNotNull(state.otaria).players.getValue(bot).position
        val next = destination(state)
        OtariaBoard.distance(before, next) shouldBe 1L
        OtariaBoard.distance(next, OtariaLocation.BALSHAN_BAY.position) shouldBe
            OtariaBoard.distance(before, OtariaLocation.BALSHAN_BAY.position) - 1
    }

    test("an actual movement response reaches the matching star and the existing action claims victory") {
        val target = OtariaLocation.ORDER_ENCAMPMENT
        val base = player(reserve(game(), mirariId to mirari)) { it.copy(locationCards = listOf(target)) }
        val mode = requireNotNull(base.otaria)
        val main = base.copy(step = Step.PRECOMBAT_MAIN, phase = Step.PRECOMBAT_MAIN.phase,
            otaria = mode.copy(turn = OtariaTurn(bot, 1, OtariaTurnAction.MOVE)))
        val asked = services.turnManager.advanceStep(main).state
        val decision = requireNotNull(asked.pendingDecision)
        val response = AIPlayer.create(registry, bot).respondToDecision(asked, decision)
        val moved = processor.process(asked, SubmitDecision(bot, response)).result
        moved.error shouldBe null
        moved.state.otaria?.players?.get(bot)?.position shouldBe target.position
        val claim = AIPlayer.create(registry, bot).chooseAction(moved.state)
        claim shouldBe ClaimOtariaMirariVictory(bot, mirariId)
        val won = processor.process(moved.state, claim).result
        won.error shouldBe null
        won.state.winnerId shouldBe bot
    }

    test("controlled battlefield Mirari drives the route but a stolen or token Mirari does not") {
        val target = OtariaLocation.BALSHAN_BAY
        val base = player(game()) {
            it.copy(hasExploredLocationAwayFromStart = true, locationCards = listOf(target))
        }
        val controlled = battlefield(base, bot, mirariId, mirari)
        action(controlled) shouldBe OtariaTurnAction.MOVE
        val stolen = controlled.updateEntity(mirariId) { it.with(ControllerComponent(seats[1])) }
        action(stolen) shouldBe OtariaTurnAction.EXPLORE
        action(controlled.updateEntity(mirariId) { it.with(TokenComponent) }) shouldBe OtariaTurnAction.EXPLORE
    }

    test("starting stars and exhausted stars are skipped while eligible stars are explored by a ready army") {
        val base = readyArmy(game())
        val baseMode = requireNotNull(base.otaria)
        val distant = base.copy(otaria = baseMode.copy(players = baseMode.players.mapValues { (id, p) ->
            if (id == bot) p else p.copy(position = OtariaLocation.CEPHALID_SOUTHERN_PALACE.position)
        }))
        action(distant) shouldBe OtariaTurnAction.MOVE
        val eligible = player(distant) { it.copy(hasExploredLocationAwayFromStart = true) }
        action(eligible) shouldBe OtariaTurnAction.EXPLORE
        val mode = requireNotNull(eligible.otaria)
        action(eligible.copy(otaria = mode.copy(explorationsUsed = mode.explorationsUsed +
            (OtariaLocation.KROSIAN_FOREST to OtariaLocation.KROSIAN_FOREST.explorationLimit(3))))) shouldBe
            OtariaTurnAction.MOVE
        val empty = mode.copy(starPile = OtariaExplorationPile(emptyList()))
        action(eligible.copy(otaria = empty)) shouldBe OtariaTurnAction.EXPLORE
        action(eligible.copy(otaria = empty.copy(starPile = OtariaExplorationPile(emptyList(),
            listOf(OtariaPileCard("Discarded reward", OtariaCardKind.NONCREATURE)))))) shouldBe OtariaTurnAction.EXPLORE
    }

    test("weaker explorers avoid a contested next stop without lengthening a Mirari route") {
        val base = player(reserve(game(), mirariId to mirari)) {
            it.copy(locationCards = listOf(OtariaLocation.BALSHAN_BAY))
        }
        val unopposed = destination(base)
        val mode = requireNotNull(base.otaria)
        val contested = battlefield(base.copy(otaria = mode.copy(players = mode.players +
            (seats[1] to mode.players.getValue(seats[1]).copy(position = unopposed)))),
            seats[1], giantId, expensive)
        val next = destination(contested)
        (next == unopposed) shouldBe false
        OtariaBoard.distance(next, OtariaLocation.BALSHAN_BAY.position) shouldBe
            OtariaBoard.distance(unopposed, OtariaLocation.BALSHAN_BAY.position)
    }

    test("standard navigation avoids a stronger opponent occupying a rewarding star") {
        val base = game()
        val unopposed = destination(base)
        val mode = requireNotNull(base.otaria)
        val contested = battlefield(base.copy(otaria = mode.copy(players = mode.players +
            (seats[1] to mode.players.getValue(seats[1]).copy(position = unopposed)))),
            seats[1], giantId, expensive)
        (destination(contested) == unopposed) shouldBe false
    }

    test("a favorable nearby fight chooses combat without replacing the Magic combat advisor") {
        val base = battlefield(game(), bot, bearId, bear)
        val mode = requireNotNull(base.otaria)
        val state = base.copy(otaria = mode.copy(players = mode.players +
            (seats[1] to mode.players.getValue(seats[1]).copy(position = mode.players.getValue(bot).position))))
        action(state) shouldBe OtariaTurnAction.COMBAT
        policy.respond(state, ChooseColorDecision("magic", bot, "Choose mana", DecisionContext())) shouldBe null
    }

    test("exploration selects terrain until ready, then the star and free land independently of labels") {
        val choices = listOf(OtariaExplorationChoice(false, false), OtariaExplorationChoice(true, false),
            OtariaExplorationChoice(false, true), OtariaExplorationChoice(true, true))
        val asked = game().suspendForDecision(
            question = { ChooseOptionDecision(it, bot, "Translated", DecisionContext(),
                options = listOf("A", "B", "C", "D")) },
            answer = OtariaExplorationChoiceContinuation(bot, choices),
        ).state
        (policy.respond(asked, requireNotNull(asked.pendingDecision)) as OptionChosenResponse).optionIndex shouldBe 2
        val ready = readyArmy(player(game()) { it.copy(hasExploredLocationAwayFromStart = true) }).suspendForDecision(
            question = { ChooseOptionDecision(it, bot, "Translated", DecisionContext(),
                options = listOf("A", "B", "C", "D")) },
            answer = OtariaExplorationChoiceContinuation(bot, choices),
        ).state
        (policy.respond(ready, requireNotNull(ready.pendingDecision)) as OptionChosenResponse).optionIndex shouldBe 3
    }

    test("weak or vulnerable armies explore terrain even when a star is available") {
        val initial = player(game()) { it.copy(hasExploredLocationAwayFromStart = true) }
        val mode = requireNotNull(initial.otaria)
        val eligible = initial.copy(otaria = mode.copy(players = mode.players.mapValues { (id, p) ->
            if (id == bot) p else p.copy(position = OtariaLocation.CEPHALID_SOUTHERN_PALACE.position)
        }))
        action(eligible) shouldBe OtariaTurnAction.EXPLORE
        val noAerialDefense = battlefield(battlefield(battlefield(eligible, bot, EntityId("a"), fighter),
            bot, EntityId("b"), fighter), bot, EntityId("c"), fighter)
        action(noAerialDefense) shouldBe OtariaTurnAction.EXPLORE
        val choices = listOf(OtariaExplorationChoice(false, true), OtariaExplorationChoice(true, true))
        fun exploration(state: GameState): Int {
            val asked = state.suspendForDecision(
                question = { ChooseOptionDecision(it, bot, "Explore", DecisionContext(), options = listOf("terrain", "star")) },
                answer = OtariaExplorationChoiceContinuation(bot, choices),
            ).state
            return (policy.respond(asked, requireNotNull(asked.pendingDecision)) as OptionChosenResponse).optionIndex
        }
        exploration(eligible) shouldBe 0
        exploration(noAerialDefense) shouldBe 0
        val army = readyArmy(eligible)
        exploration(army) shouldBe 1
        val tapped = army.copy(step = Step.OTARIA_EXPLORATION)
            .updateEntity(EntityId("reach")) { it.with(TappedComponent) }
        exploration(tapped) shouldBe 0
        exploration(army.updateEntity(bot) { it.with(LifeTotalComponent(8)) }) shouldBe 0
        exploration(army.copy(zones = army.zones + (ZoneKey(bot, Zone.LIBRARY) to
            army.getZone(bot, Zone.LIBRARY).take(4)))) shouldBe 0
        val armyMode = requireNotNull(army.otaria)
        exploration(army.copy(otaria = armyMode.copy(starPile = OtariaExplorationPile(emptyList())))) shouldBe 0
        exploration(army.copy(otaria = armyMode.copy(explorationsUsed = armyMode.explorationsUsed +
            (OtariaLocation.KROSIAN_FOREST to OtariaLocation.KROSIAN_FOREST.explorationLimit(3))))) shouldBe 0
    }

    test("a depleted library with no reserve never picks combat and instead explores for land") {
        val base = battlefield(game(0), bot, bearId, bear)
        val mode = requireNotNull(base.otaria)
        val nearby = base.copy(otaria = mode.copy(players = mode.players +
            (seats[1] to mode.players.getValue(seats[1]).copy(position = mode.players.getValue(bot).position))))
        action(nearby) shouldBe OtariaTurnAction.EXPLORE
    }

    test("safe terrain exploration acquires a land that the next turn prepares into the library") {
        val base = game()
        val mode = requireNotNull(base.otaria)
        val main = base.copy(step = Step.PRECOMBAT_MAIN, phase = Step.PRECOMBAT_MAIN.phase,
            otaria = mode.copy(
                turn = OtariaTurn(bot, 1, OtariaTurnAction.EXPLORE),
                colorPiles = mode.colorPiles + (Color.GREEN to OtariaExplorationPile(emptyList())),
            ))
        val asked = services.turnManager.advanceStep(main).state
        val decision = requireNotNull(asked.pendingDecision)
        val response = AIPlayer.create(registry, bot).respondToDecision(asked, decision)
        val explored = processor.process(asked, SubmitDecision(bot, response)).result
        explored.error shouldBe null
        val reserve = requireNotNull(explored.state.otaria).players.getValue(bot).reserve
        reserve.size shouldBe 1
        action(explored.state.copy(turnNumber = 2)) shouldBe OtariaTurnAction.PREPARE
        val nextMode = requireNotNull(explored.state.otaria)
        val upkeep = explored.state.copy(
            turnNumber = 2, step = Step.UPKEEP, phase = Step.UPKEEP.phase,
            otaria = nextMode.copy(turn = OtariaTurn(bot, 2, OtariaTurnAction.PREPARE)),
        )
        val preparing = services.turnManager.advanceStep(upkeep).state
        val selection = requireNotNull(preparing.pendingDecision)
        val selected = AIPlayer.create(registry, bot).respondToDecision(preparing, selection)
        val prepared = processor.process(preparing, SubmitDecision(bot, selected)).result
        prepared.error shouldBe null
        (reserve.single() in prepared.state.getZone(bot, Zone.LIBRARY)) shouldBe true
    }

    test("preparation selects a castable curve filler instead of a giant or the campaign objective") {
        var state = reserve(game(4), giantId to expensive, mirariId to mirari, bearId to bear)
        repeat(2) { state = battlefield(state, bot, EntityId("source-$it"), forest) }
        val mode = requireNotNull(state.otaria)
        val prepared = services.turnManager.advanceStep(state.copy(otaria = mode.copy(
            turn = OtariaTurn(bot, 1, OtariaTurnAction.PREPARE),
        ))).state
        val decision = prepared.pendingDecision as SelectCardsDecision
        val response = AIPlayer.create(registry, bot).respondToDecision(prepared, decision) as CardsSelectedResponse
        response.selectedCards shouldBe listOf(bearId, giantId)
        FastDecisionResponder().respond(prepared, decision, bot) shouldBe response
        val result = processor.process(prepared, SubmitDecision(bot, response)).result
        result.error shouldBe null
        result.state.getZone(bot, Zone.LIBRARY).size shouldBe 6
        (bearId in result.state.getZone(bot, Zone.LIBRARY)) shouldBe true
        result.state.otaria?.players?.get(bot)?.reserve shouldBe listOf(mirariId)
        result.events.filterIsInstance<OtariaPreparedEvent>().single().cardIds shouldBe listOf(bearId, giantId)
    }

    test("mana-starved preparation prefers a land to an off-color expensive spell") {
        val state = reserve(game(0), giantId to expensive, forestId to forest)
        val asked = state.suspendForDecision(
            question = { SelectCardsDecision(it, bot, "Prepare", DecisionContext(),
                options = listOf(giantId, forestId), minSelections = 0, maxSelections = 2) },
            answer = OtariaPreparationChoiceContinuation(bot),
        ).state
        (policy.respond(asked, requireNotNull(asked.pendingDecision)) as CardsSelectedResponse).selectedCards shouldBe
            listOf(forestId, giantId)
    }

    test("Mirari alone can buffer an empty library but is never routine library filler") {
        val state = reserve(game(0), mirariId to mirari)
        val asked = state.suspendForDecision(
            question = { SelectCardsDecision(it, bot, "Prepare", DecisionContext(),
                options = listOf(mirariId), minSelections = 0, maxSelections = 1) },
            answer = OtariaPreparationChoiceContinuation(bot),
        ).state
        (policy.respond(asked, requireNotNull(asked.pendingDecision)) as CardsSelectedResponse).selectedCards shouldBe
            listOf(mirariId)
        val routine = reserve(game(10), mirariId to mirari).suspendForDecision(
            question = { SelectCardsDecision(it, bot, "Prepare", DecisionContext(),
                options = listOf(mirariId), minSelections = 0, maxSelections = 1) },
            answer = OtariaPreparationChoiceContinuation(bot),
        ).state
        (policy.respond(routine, requireNotNull(routine.pendingDecision)) as CardsSelectedResponse).selectedCards shouldBe
            emptyList()
    }

    test("development adds reserve cards and draws even before the deck-out emergency") {
        val developed = battlefield(battlefield(game(), bot, EntityId("source-1"), forest),
            bot, EntityId("source-2"), forest)
        action(reserve(developed, bearId to bear)) shouldBe OtariaTurnAction.PREPARE
        action(reserve(developed, forestId to forest)) shouldBe OtariaTurnAction.PREPARE
        val fewCards = developed.copy(zones = developed.zones + (ZoneKey(bot, Zone.HAND) to
            developed.getZone(bot, Zone.HAND).take(3)))
        action(fewCards) shouldBe OtariaTurnAction.PREPARE
        action(fewCards.copy(zones = fewCards.zones + (ZoneKey(bot, Zone.LIBRARY) to emptyList()))) shouldBe
            OtariaTurnAction.EXPLORE
    }

    test("discovery accepts a location card and unrelated Magic choices stay outside the policy") {
        val asked = game().suspendForDecision(
            question = { YesNoDecision(it, bot, "Take location", DecisionContext()) },
            answer = OtariaDiscoveryChoiceContinuation(bot),
        ).state
        val decision = requireNotNull(asked.pendingDecision)
        policy.respond(asked, decision) shouldBe YesNoResponse(decision.id, true)
        policy.respond(asked.copy(otaria = null), decision) shouldBe null
        policy.respond(game(), ChooseOptionDecision("magic", bot, "Choose your action for this turn",
            DecisionContext(sourceName = "Explorer of Otaria"), options = listOf("Move", "Explore"))) shouldBe null
    }

    test("campaign decisions do not depend on hidden opponent cards or exploration order") {
        val base = game()
        val mode = requireNotNull(base.otaria)
        val hidden = base.copy(otaria = mode.copy(
            players = mode.players + (seats[1] to mode.players.getValue(seats[1]).copy(
                reserve = listOf(EntityId("unseen-Mirari")), locationCards = listOf(OtariaLocation.BALSHAN_BAY))),
            starPile = OtariaExplorationPile(listOf(OtariaPileCard("Hidden boss", OtariaCardKind.BOSS))),
        ))
        action(hidden) shouldBe action(base)
        destination(hidden) shouldBe destination(base)
    }
})
