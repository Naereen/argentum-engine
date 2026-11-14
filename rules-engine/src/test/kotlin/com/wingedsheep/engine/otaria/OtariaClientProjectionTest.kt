package com.wingedsheep.engine.otaria

import com.wingedsheep.engine.core.CardEntityFactory
import com.wingedsheep.engine.core.ZoneChangeEvent
import com.wingedsheep.engine.handlers.PredicateEvaluator
import com.wingedsheep.engine.handlers.effects.FaceDownTurnUp
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.identity.FaceDownComponent
import com.wingedsheep.engine.state.components.identity.FaceDownModeComponent
import com.wingedsheep.engine.state.components.identity.PlayerComponent
import com.wingedsheep.engine.state.components.player.HotseatControlComponent
import com.wingedsheep.engine.state.FACE_DOWN_DISPLAY_NAME
import com.wingedsheep.engine.view.ClientStateTransformer
import com.wingedsheep.engine.view.ClientEvent
import com.wingedsheep.engine.view.ClientEventTransformer
import com.wingedsheep.engine.view.Visibility
import com.wingedsheep.engine.view.StateDiffCalculator
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.CardScript
import com.wingedsheep.sdk.model.CreatureStats
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.KeywordAbility
import com.wingedsheep.sdk.scripting.effects.FaceDownMode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class OtariaClientProjectionTest : FunSpec({
    val p1 = EntityId("player-1")
    val p2 = EntityId("player-2")
    val p3 = EntityId("player-3")
    val reserve = EntityId("private-reserve-card")
    val seats = listOf(p1, p2, p3)

    fun morph(name: String) = CardDefinition(
        name = name,
        manaCost = ManaCost.parse("{2}{G}"),
        typeLine = TypeLine.creature(setOf(Subtype("Wizard"))),
        oracleText = "Morph {G}",
        creatureStats = CreatureStats(2, 2),
        keywordAbilities = listOf(KeywordAbility.Morph(ManaCost.parse("{G}"))),
        script = CardScript.creature(),
    )
    val registry = CardRegistry().also {
        it.register(morph("Hidden Morph A"))
        it.register(morph("Hidden Morph B"))
        it.register(CardDefinition.creature(
            name = "Face Up Creature",
            manaCost = ManaCost.parse("{G}"),
            subtypes = setOf(Subtype("Elf")),
            power = 1,
            toughness = 1,
        ))
    }
    val transformer = ClientStateTransformer(registry, predicateEvaluator = PredicateEvaluator(registry))
    val json = Json { encodeDefaults = true; allowStructuredMapKeys = true }

    fun state(): GameState {
        val locations = OtariaLocation.entries.take(seats.size)
        val players = seats.zip(locations).associate { (id, location) ->
            id to OtariaPlayerState(
                startingLocation = location,
                position = location.position,
                locationCards = if (id == p1) listOf(OtariaLocation.CABAL_CITY) else emptyList(),
                reserve = if (id == p1) listOf(reserve) else emptyList(),
                treasureTokens = if (id == p1) mapOf(OtariaTreasureToken.SCRAP to 1) else emptyMap(),
            )
        }
        val mode = OtariaState(
            players = players,
            explorationsUsed = OtariaLocation.entries.associateWith { 0 },
            starPile = OtariaExplorationPile(listOf(OtariaPileCard("Secret Boss", OtariaCardKind.BOSS))),
            colorPiles = Color.entries.associateWith {
                OtariaExplorationPile(listOf(OtariaPileCard("Secret $it", OtariaCardKind.CREATURE)))
            },
            treasurePile = listOf(OtariaTreasure.Card("Hidden treasure")),
            locationPile = listOf(OtariaLocation.BALSHAN_BAY),
            pendingExploration = OtariaPendingExploration(
                p1, p2, OtariaExplorationSource.Terrain(Color.GREEN),
                OtariaPileCard("Hidden Morph A", OtariaCardKind.CREATURE),
            ),
        )
        return GameState(
            entities = seats.associateWith { ComponentContainer.of(PlayerComponent(it.value)) },
            turnOrder = seats,
            activePlayerId = p1,
            priorityPlayerId = p1,
            otaria = mode,
        ).withEntity(reserve, ComponentContainer.of(CardComponent(
            cardDefinitionId = "Secret Reserve A",
            name = "Secret Reserve A",
            manaCost = ManaCost.parse("{2}"),
            typeLine = TypeLine(cardTypes = setOf(CardType.ARTIFACT)),
            ownerId = p1,
        )))
    }

    test("the board and counts are public but only the owner sees their reserve and location cards") {
        val state = state()
        val owner = requireNotNull(transformer.transform(state, p1).otaria)
        owner.tiles shouldHaveSize 189
        owner.locations shouldHaveSize 8
        owner.locations.single { it.location == OtariaLocation.CABAL_CITY }.limit shouldBe 2
        owner.players.single { it.playerId == p1 }.reserveCount shouldBe 1
        val privateData = requireNotNull(owner.players.single { it.playerId == p1 }.privateData)
        privateData.reserve.single().name shouldBe "Secret Reserve A"
        privateData.locationCards shouldBe listOf(OtariaLocation.CABAL_CITY)
        privateData.treasureTokens shouldBe mapOf(OtariaTreasureToken.SCRAP to 1)

        for ((viewer, spectator) in listOf(p2 to false, p3 to false, p1 to true, p2 to true)) {
            val view = transformer.transform(state, viewer, isSpectator = spectator)
            requireNotNull(view.otaria).players.single { it.playerId == p1 }.privateData shouldBe null
            val payload = json.encodeToString(view)
            payload shouldNotContain "Secret Reserve A"
            payload shouldNotContain reserve.value
            payload shouldNotContain "Secret Boss"
            payload shouldNotContain "Hidden treasure"
        }
    }

    test("only the manager can identify a morph, including when a spectator uses the manager's id") {
        val state = state()
        for ((viewer, spectator) in listOf(p1 to false, p3 to false, p2 to true)) {
            val pending = requireNotNull(transformer.transform(state, viewer, spectator).otaria?.pendingExploration)
            pending.faceDown shouldBe true
            pending.card shouldBe null
            json.encodeToString(pending) shouldNotContain "Hidden Morph A"
        }

        val manager = requireNotNull(transformer.transform(state, p2).otaria?.pendingExploration)
        manager.faceDown shouldBe true
        manager.card?.name shouldBe "Hidden Morph A"

        val hotseat = state.updateEntity(p2) { it.with(HotseatControlComponent(p1)) }
        transformer.transform(hotseat, p1).otaria?.pendingExploration?.card?.name shouldBe "Hidden Morph A"
        transformer.transform(hotseat, p1, isSpectator = true).otaria?.pendingExploration?.card shouldBe null
    }

    test("a pending event includes its supplied proxy artwork for players and spectators") {
        val initial = state()
        val mode = requireNotNull(initial.otaria)
        val pending = requireNotNull(mode.pendingExploration)
        val event = initial.copy(otaria = mode.copy(
            pendingExploration = pending.copy(card = OtariaPileCard("Ev. Naturalisation", OtariaCardKind.EVENT)),
        ))
        for ((viewer, spectator) in listOf(p1 to false, p2 to false, p3 to true)) {
            val projected = requireNotNull(transformer.transform(event, viewer, spectator).otaria?.pendingExploration)
            projected.faceDown shouldBe false
            projected.card?.imageUri shouldBe "/images/otaria/events/naturalisation.png"
        }
    }

    test("an encounter manager sees its face-down permanent without controlling the other seats") {
        val actor = EntityId("encounter-actor")
        val creatureId = EntityId("encounter-morph")
        val handId = EntityId("encounter-hand")
        val otherHandId = EntityId("other-hand")
        val initial = state()
        val mode = requireNotNull(initial.otaria)
        val creature = registry.requireCard("Hidden Morph A")
        val encounter = OtariaEncounter(
            explorerId = p1,
            managerId = p2,
            position = mode.players.getValue(p1).position,
            source = OtariaExplorationSource.Terrain(Color.GREEN),
            primaryId = creatureId,
        )
        val current = initial.copy(otaria = mode.copy(
            pendingExploration = null,
            encounters = mapOf(actor to encounter),
        )).withEntity(actor, ComponentContainer.of(PlayerComponent("Encounter")))
            .withEntity(creatureId, CardEntityFactory.create(creature, actor)
                .with(FaceDownComponent)
                .with(FaceDownModeComponent(FaceDownMode.MORPH))
                .with(requireNotNull(FaceDownTurnUp.dataFor(creature, creature.name, FaceDownMode.MORPH))))
            .addToZone(ZoneKey(actor, Zone.BATTLEFIELD), creatureId)
            .withEntity(handId, CardEntityFactory.create(registry.requireCard("Hidden Morph B"), actor))
            .addToZone(ZoneKey(actor, Zone.HAND), handId)
            .withEntity(otherHandId, CardEntityFactory.create(creature, p1))
            .addToZone(ZoneKey(p1, Zone.HAND), otherHandId)

        current.actorFor(actor) shouldBe p2
        current.activePlayers shouldBe seats
        val manager = transformer.transform(current, p2)
        manager.hotseat shouldBe false
        manager.youAreHijacking shouldBe null
        manager.cards.getValue(creatureId).name shouldBe creature.name
        manager.cards.getValue(creatureId).morphCost shouldBe "{G}"
        manager.cards.getValue(handId).name shouldBe "Hidden Morph B"
        manager.cards.containsKey(otherHandId) shouldBe false

        for ((viewer, spectator) in listOf(p1 to false, p3 to false, p2 to true)) {
            val view = transformer.transform(current, viewer, spectator)
            view.cards.getValue(creatureId).name shouldBe FACE_DOWN_DISPLAY_NAME
            view.cards.getValue(creatureId).revealedName shouldBe null
            view.cards.getValue(creatureId).morphCost shouldBe null
            view.cards.containsKey(handId) shouldBe false
        }
        val visibility = Visibility(registry, conditionEvaluator = PredicateEvaluator(registry).conditions)
        visibility.isCardIdentityVisibleTo(current, Zone.BATTLEFIELD, creatureId, p2) shouldBe true
        visibility.isCardIdentityVisibleTo(current, Zone.BATTLEFIELD, creatureId, p1) shouldBe false

        val entered = ZoneChangeEvent(
            creatureId, creature.name, null, Zone.BATTLEFIELD, actor, copyOfOriginalName = "Secret original",
        )
        fun entryFor(state: GameState, viewer: EntityId): ClientEvent.PermanentEntered =
            ClientEventTransformer.transform(listOf(entered), viewer, state, visibility).single() as ClientEvent.PermanentEntered
        entryFor(current, p2).cardName shouldBe creature.name
        for (viewer in listOf(p1, p3, EntityId("spectator"))) {
            val event = entryFor(current, viewer)
            event.cardName shouldBe FACE_DOWN_DISPLAY_NAME
            event.copyOf shouldBe null
        }

        val stolen = current.updateEntity(creatureId) { it.with(ControllerComponent(p3)) }
        transformer.transform(stolen, p2).cards.getValue(creatureId).name shouldBe FACE_DOWN_DISPLAY_NAME
        transformer.transform(stolen, p3).cards.getValue(creatureId).name shouldBe creature.name
        entryFor(stolen, p2).cardName shouldBe FACE_DOWN_DISPLAY_NAME
        entryFor(stolen, p3).cardName shouldBe creature.name

        val controlledManager = current.updateEntity(p2) { it.with(HotseatControlComponent(p1)) }
        controlledManager.actorFor(actor) shouldBe p1
        transformer.transform(controlledManager, p1).cards.getValue(creatureId).name shouldBe creature.name
        transformer.transform(controlledManager, p1, isSpectator = true)
            .cards.getValue(creatureId).name shouldBe FACE_DOWN_DISPLAY_NAME
    }

    test("non-morph explorations are visible to all and missing creature definitions fail explicitly") {
        val state = state()
        val mode = requireNotNull(state.otaria)
        val pending = requireNotNull(mode.pendingExploration)
        val faceUp = state.copy(otaria = mode.copy(
            pendingExploration = pending.copy(card = OtariaPileCard("Face Up Creature", OtariaCardKind.CREATURE)),
        ))
        for ((viewer, spectator) in listOf(p1 to false, p2 to false, p3 to true)) {
            val view = requireNotNull(transformer.transform(faceUp, viewer, spectator).otaria?.pendingExploration)
            view.faceDown shouldBe false
            view.card?.name shouldBe "Face Up Creature"
        }
        val missing = state.copy(otaria = mode.copy(
            pendingExploration = pending.copy(card = OtariaPileCard("Missing Creature", OtariaCardKind.CREATURE)),
        ))
        shouldThrow<IllegalArgumentException> { transformer.transform(missing, p1) }
    }

    test("different hidden worlds produce identical views for an opponent and a spectator") {
        val first = state()
        val mode = requireNotNull(first.otaria)
        val player = mode.players.getValue(p1)
        val pending = requireNotNull(mode.pendingExploration)
        val second = first.updateEntity(reserve) { container ->
            val card = requireNotNull(container.get<CardComponent>())
            container.with(card.copy(cardDefinitionId = "Secret Reserve B", name = "Secret Reserve B"))
        }.copy(otaria = mode.copy(
            players = mode.players + (p1 to player.copy(
                locationCards = listOf(OtariaLocation.PARDIC_MOUNTAINS),
                treasureTokens = mapOf(OtariaTreasureToken.ROCK to 1),
            )),
            starPile = OtariaExplorationPile(listOf(OtariaPileCard("Another Boss", OtariaCardKind.BOSS))),
            treasurePile = listOf(OtariaTreasure.Card("Another treasure")),
            locationPile = listOf(OtariaLocation.ORDER_ENCAMPMENT),
            pendingExploration = pending.copy(card = OtariaPileCard("Hidden Morph B", OtariaCardKind.CREATURE)),
        ))
        for ((viewer, spectator) in listOf(p3 to false, p2 to true)) {
            transformer.transform(first, viewer, spectator) shouldBe transformer.transform(second, viewer, spectator)
        }
    }

    test("Otaria state is sent only when changed and has an explicit clearing delta") {
        val state = state()
        val before = transformer.transform(state, p1)
        val mode = requireNotNull(state.otaria)
        val moved = mode.players.getValue(p1).copy(position = OtariaPosition(6, 6))
        val after = transformer.transform(state.copy(otaria = mode.copy(players = mode.players + (p1 to moved))), p1)
        StateDiffCalculator.computeDelta(before, before).otaria shouldBe null
        StateDiffCalculator.computeDelta(before, after).otaria shouldBe after.otaria
        val cleared = transformer.transform(state.copy(otaria = null), p1)
        StateDiffCalculator.computeDelta(after, cleared).otariaCleared shouldBe true
        StateDiffCalculator.computeDelta(cleared, after).otaria shouldBe after.otaria
    }
})
