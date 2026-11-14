package com.wingedsheep.engine.handlers.effects

import com.wingedsheep.engine.core.CardEntityFactory
import com.wingedsheep.engine.core.ZoneChangeEvent
import com.wingedsheep.engine.event.TriggerDetector
import com.wingedsheep.engine.handlers.PredicateEvaluator
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.CountersComponent
import com.wingedsheep.engine.state.components.battlefield.TappedComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.identity.ExiledFromZoneComponent
import com.wingedsheep.engine.state.components.identity.FaceDownComponent
import com.wingedsheep.engine.state.components.identity.LifeTotalComponent
import com.wingedsheep.engine.state.components.identity.OwnerComponent
import com.wingedsheep.engine.state.components.identity.PlayerComponent
import com.wingedsheep.engine.state.components.identity.TokenComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.state.components.player.PermanentsEnteredUnderControlThisTurnComponent
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.CounterType
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.CreatureStats
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.CantEnterTheBattlefield
import com.wingedsheep.sdk.scripting.EntersWithCounters
import com.wingedsheep.sdk.scripting.EventPattern
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.GrantSubtype
import com.wingedsheep.sdk.scripting.LoseAllAbilities
import com.wingedsheep.sdk.scripting.PermanentsEnterTapped
import com.wingedsheep.sdk.scripting.PutOntoBattlefieldThen
import com.wingedsheep.sdk.scripting.RedirectZoneChange
import com.wingedsheep.sdk.scripting.filters.unified.GroupFilter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class NewBattlefieldObjectsTest : FunSpec({
    val player = EntityId("player")
    val opponent = EntityId("opponent")
    val observer = card("New Entry Observer") {
        typeLine = "Creature — Human"
        power = 2
        toughness = 2
        triggeredAbility {
            trigger = Triggers.another(GameObjectFilter.Creature.youControl()).enters()
            effect = Effects.GainLife(1)
        }
    }
    val counters = card("New Entry Counters") {
        typeLine = "Creature — Human"
        power = 1
        toughness = 1
        replacementEffect(EntersWithCounters(count = 2, selfOnly = true))
    }
    val counterSource = card("New Entry Counter Source") {
        typeLine = "Enchantment"
        replacementEffect(EntersWithCounters(count = 1))
    }
    val tapSource = card("New Entry Tap Source") {
        typeLine = "Creature — Human"
        power = 1
        toughness = 1
        replacementEffect(PermanentsEnterTapped(
            appliesTo = EventPattern.ZoneChangeEvent(filter = GameObjectFilter.Creature, to = Zone.BATTLEFIELD),
        ))
    }
    val lock = card("New Entry Lock") {
        typeLine = "Creature — Human"
        power = 1
        toughness = 1
        staticAbility { ability = CantEnterTheBattlefield(GameObjectFilter.Creature) }
    }
    val graveyardLock = card("New Entry Graveyard Lock") {
        typeLine = "Enchantment"
        staticAbility {
            ability = CantEnterTheBattlefield(GameObjectFilter.Creature, fromZones = setOf(Zone.GRAVEYARD))
        }
    }
    val redirect = card("New Entry Redirect") {
        typeLine = "Enchantment"
        replacementEffect(RedirectZoneChange(
            newDestination = Zone.EXILE,
            appliesTo = EventPattern.ZoneChangeEvent(filter = GameObjectFilter.Creature, to = Zone.BATTLEFIELD),
        ))
    }
    val riderSource = card("New Entry Rider Source") {
        typeLine = "Creature — Human"
        power = 1
        toughness = 1
        replacementEffect(PutOntoBattlefieldThen(
            effect = Effects.GainLife(1),
            appliesTo = EventPattern.ZoneChangeEvent(filter = GameObjectFilter.Creature, to = Zone.BATTLEFIELD),
        ))
    }
    val abilityRemover = card("New Entry Ability Remover") {
        typeLine = "Creature — Human"
        power = 1
        toughness = 1
        staticAbility { ability = LoseAllAbilities(GroupFilter.AllCreatures) }
    }
    val subtypeGranter = card("New Entry Subtype Granter") {
        typeLine = "Creature — Human"
        power = 1
        toughness = 1
        staticAbility { ability = GrantSubtype("Goblin", GroupFilter.AllCreatures) }
    }
    val goblinRider = card("New Entry Goblin Rider") {
        typeLine = "Enchantment"
        replacementEffect(PutOntoBattlefieldThen(
            effect = Effects.GainLife(1),
            appliesTo = EventPattern.ZoneChangeEvent(
                filter = GameObjectFilter.Creature.withSubtype("Goblin"), to = Zone.BATTLEFIELD,
            ),
        ))
    }
    val registry = CardRegistry().also { cards ->
        listOf(
            observer, counters, counterSource, tapSource, lock, graveyardLock, redirect, riderSource,
            abilityRemover, subtypeGranter, goblinRider,
        )
            .forEach(cards::register)
    }
    val evaluator = PredicateEvaluator(registry)
    val zones = ZoneTransitionService(registry, evaluator)
    val detector = TriggerDetector(
        registry, predicateEvaluator = evaluator, conditionEvaluator = evaluator.conditions,
    )

    fun initial(): GameState = GameState(
        entities = listOf(player, opponent).associateWith {
            ComponentContainer.of(PlayerComponent(it.value), LifeTotalComponent(20), ManaPoolComponent())
        },
        turnOrder = listOf(player, opponent),
        activePlayerId = player,
        priorityPlayerId = player,
    )

    fun newCard(state: GameState, id: EntityId, definition: CardDefinition): GameState =
        state.withEntity(id, CardEntityFactory.create(definition, player))

    fun newToken(state: GameState, id: EntityId): GameState = state.withEntity(
        id,
        ComponentContainer.of(
            CardComponent(
                cardDefinitionId = "unregistered-otaria-token",
                name = "New Goblin Token",
                manaCost = ManaCost.ZERO,
                typeLine = TypeLine(cardTypes = setOf(CardType.CREATURE), subtypes = setOf(Subtype.GOBLIN)),
                baseStats = CreatureStats(1, 1),
                colors = setOf(Color.RED),
                ownerId = player,
            ),
            OwnerComponent(player),
            ControllerComponent(player),
            TokenComponent,
        ),
    )

    fun existing(state: GameState, id: EntityId, definition: CardDefinition): GameState {
        val inHand = newCard(state, id, definition).addToZone(ZoneKey(player, Zone.HAND), id)
        return zones.moveToZone(inHand, id, Zone.BATTLEFIELD).state
    }

    fun count(state: GameState, id: EntityId): Int =
        state.getEntity(id)?.get<CountersComponent>()?.getCount(CounterType.PLUS_ONE_PLUS_ONE) ?: 0

    test("a card and a token enter together and observe the same entry event") {
        val watcher = EntityId("watcher")
        val token = EntityId("token")
        val before = newToken(newCard(initial(), watcher, observer), token)
        val result = zones.enterNewObjects(before, listOf(NewBattlefieldObject(watcher), NewBattlefieldObject(token)))
        before.getBattlefield() shouldBe emptyList()
        result.state.getBattlefield().toSet() shouldBe setOf(watcher, token)
        val entries = result.events.filterIsInstance<ZoneChangeEvent>()
        entries.size shouldBe 2
        entries.map { it.fromZone } shouldBe listOf(null, null)
        entries.map { it.simultaneousEntryGroup }.distinct().size shouldBe 1
        entries.first().simultaneousEntryGroup shouldNotBe null
        entries.forEach { it.newObject shouldBe result.state.objectRef(it.entityId) }
        detector.detectTriggers(result.state, result.events).count { it.sourceId == watcher } shouldBe 1
    }

    test("printed and global entry counters include an unregistered token but not a face-down card's text") {
        val source = EntityId("counter-source")
        val faceUp = EntityId("face-up")
        val faceDown = EntityId("face-down")
        val token = EntityId("token")
        val withSource = existing(initial(), source, counterSource)
        val before = newToken(newCard(newCard(withSource, faceUp, counters), faceDown, counters), token)
        val result = zones.enterNewObjects(before, listOf(
            NewBattlefieldObject(faceUp),
            NewBattlefieldObject(faceDown, ZoneEntryOptions(faceDown = true)),
            NewBattlefieldObject(token),
        ))
        count(result.state, faceUp) shouldBe 3
        count(result.state, faceDown) shouldBe 1
        count(result.state, token) shouldBe 1
        result.state.getEntity(faceDown)?.has<FaceDownComponent>() shouldBe true
        result.state.projectedState.getPower(faceDown) shouldBe 3
        count(before, faceUp) shouldBe 0
    }

    test("simultaneously entering replacement sources do not tap, lock or add riders to their peers") {
        val tapper = EntityId("tapper")
        val locker = EntityId("locker")
        val rider = EntityId("rider")
        val peer = EntityId("peer")
        val before = newCard(newCard(newCard(newCard(initial(), tapper, tapSource), locker, lock), rider, riderSource), peer, observer)
        val result = zones.enterNewObjects(before, listOf(tapper, locker, rider, peer).map { NewBattlefieldObject(it) })
        result.state.getBattlefield().toSet() shouldBe setOf(tapper, locker, rider, peer)
        result.state.getEntity(peer)?.has<TappedComponent>() shouldBe false
        result.state.pendingReplacementRiders shouldBe emptyList()
    }

    test("an existing tap replacement applies to every new entrant") {
        val tapper = EntityId("existing-tapper")
        val first = EntityId("first")
        val second = EntityId("second")
        val before = newCard(newCard(existing(initial(), tapper, tapSource), first, observer), second, counters)
        val result = zones.enterNewObjects(before, listOf(NewBattlefieldObject(first), NewBattlefieldObject(second)))
        result.state.getEntity(first)?.has<TappedComponent>() shouldBe true
        result.state.getEntity(second)?.has<TappedComponent>() shouldBe true
    }

    test("a simultaneous ability remover cannot suppress another entrant's printed replacement") {
        val remover = EntityId("remover")
        val entrant = EntityId("entrant")
        val before = newCard(newCard(initial(), remover, abilityRemover), entrant, counters)
        for (order in listOf(listOf(remover, entrant), listOf(entrant, remover))) {
            val result = zones.enterNewObjects(before, order.map { NewBattlefieldObject(it) })
            count(result.state, entrant) shouldBe 2
        }
        val existingRemover = newCard(existing(initial(), remover, abilityRemover), entrant, counters)
        count(zones.enterNewObjects(existingRemover, listOf(NewBattlefieldObject(entrant))).state, entrant) shouldBe 0
    }

    test("entry records see the whole simultaneous battlefield without rewriting older entries") {
        val older = EntityId("older")
        val entrant = EntityId("entrant")
        val granter = EntityId("granter")
        val before = newCard(newCard(existing(initial(), older, observer), entrant, observer), granter, subtypeGranter)
        val result = zones.enterNewObjects(before, listOf(NewBattlefieldObject(entrant), NewBattlefieldObject(granter)))
        val records = requireNotNull(result.state.getEntity(player)?.get<PermanentsEnteredUnderControlThisTurnComponent>())
        ("Goblin" in records.entries.single { it.entityId == older }.subtypes) shouldBe false
        ("Goblin" in records.entries.single { it.entityId == entrant }.subtypes) shouldBe true
        ("Goblin" in records.entries.single { it.entityId == granter }.subtypes) shouldBe true
    }

    test("an existing entry rider does not see another simultaneous entrant's subtype grant") {
        val rider = EntityId("existing-rider")
        val granter = EntityId("granter")
        val entrant = EntityId("entrant")
        val before = newCard(newCard(existing(initial(), rider, goblinRider), granter, subtypeGranter), entrant, observer)
        val result = zones.enterNewObjects(before, listOf(NewBattlefieldObject(granter), NewBattlefieldObject(entrant)))
        result.state.pendingReplacementRiders.map { it.hostId } shouldBe listOf(rider)
        ("Goblin" in result.state.projectedState.getSubtypes(entrant)) shouldBe true
    }

    test("zone-specific locks ignore a new object while unrestricted locks prevent its entry") {
        val source = EntityId("lock-source")
        val entrant = EntityId("entrant")
        val scoped = newCard(existing(initial(), source, graveyardLock), entrant, observer)
        val allowed = zones.enterNewObjects(scoped, listOf(NewBattlefieldObject(entrant)))
        (entrant in allowed.state.getBattlefield()) shouldBe true

        val unscoped = newCard(existing(initial(), source, lock), entrant, observer)
        val denied = zones.enterNewObjects(unscoped, listOf(NewBattlefieldObject(entrant)))
        (entrant in denied.state.getBattlefield()) shouldBe false
        denied.events shouldBe emptyList()
        denied.state.objectRef(entrant) shouldBe null
    }

    test("a redirect gives a new object no invented origin and reports its actual destination") {
        val source = EntityId("redirect")
        val entrant = EntityId("entrant")
        val before = newCard(existing(initial(), source, redirect), entrant, observer)
        val result = zones.enterNewObjects(before, listOf(NewBattlefieldObject(entrant)))
        (entrant in result.state.getZone(player, Zone.EXILE)) shouldBe true
        val transition = result.transitions.single()
        transition.fromZone shouldBe null
        transition.toZone shouldBe Zone.EXILE
        transition.requestedDestination shouldBe Zone.BATTLEFIELD
        result.state.getEntity(entrant)?.get<ExiledFromZoneComponent>() shouldBe null
    }

    test("validation rejects duplicate and previously zoned objects before moving any entrant") {
        val first = EntityId("first")
        val oldToken = EntityId("old-token")
        val staged = newToken(newCard(initial(), first, observer), oldToken)
        shouldThrow<IllegalArgumentException> {
            zones.enterNewObjects(staged, listOf(NewBattlefieldObject(first), NewBattlefieldObject(first)))
        }
        val created = zones.enterNewObjects(staged, listOf(NewBattlefieldObject(oldToken))).state
        val left = zones.moveToZone(created, oldToken, Zone.GRAVEYARD).state
        val detached = left.removeFromZone(ZoneKey(player, Zone.GRAVEYARD), oldToken)
        shouldThrow<IllegalArgumentException> {
            zones.enterNewObjects(detached, listOf(NewBattlefieldObject(first), NewBattlefieldObject(oldToken)))
        }
        (first in detached.getBattlefield()) shouldBe false
        zones.moveToZone(left, oldToken, Zone.BATTLEFIELD).state shouldBe left
    }
})
