package com.wingedsheep.engine.otaria

import com.wingedsheep.engine.core.CardEntityFactory
import com.wingedsheep.engine.core.SpellCastEvent
import com.wingedsheep.engine.core.ZoneChangeEvent
import com.wingedsheep.engine.event.TriggerDetector
import com.wingedsheep.engine.handlers.PredicateEvaluator
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.LifeTotalComponent
import com.wingedsheep.engine.state.components.identity.PlayerComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.GameObjectFilter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class OtariaEncounterTriggerTest : FunSpec({
    val players = (1..3).map { EntityId("player-$it") }
    val actor = EntityId("encounter")
    val primary = EntityId("primary")
    val stepWatcher = card("Encounter Graveyard Step Watcher") {
        typeLine = "Creature — Zombie"
        power = 1
        toughness = 1
        triggeredAbility {
            trigger = Triggers.you.beginningOf(Step.UPKEEP)
            triggerZone = Zone.GRAVEYARD
            effect = Effects.GainLife(1)
        }
    }
    val castWatcher = card("Encounter Graveyard Cast Watcher") {
        typeLine = "Creature — Zombie"
        power = 1
        toughness = 1
        triggeredAbility {
            trigger = Triggers.you.casts()
            triggerZone = Zone.GRAVEYARD
            effect = Effects.GainLife(1)
        }
    }
    val deathWatcher = card("Encounter Graveyard Death Watcher") {
        typeLine = "Creature — Zombie"
        power = 1
        toughness = 1
        triggeredAbility {
            trigger = Triggers.oneOrMore(GameObjectFilter.Creature.youControl()).die()
            triggerZone = Zone.GRAVEYARD
            effect = Effects.GainLife(1)
        }
    }
    val creature = CardDefinition.creature(
        name = "Encounter Trigger Creature",
        manaCost = ManaCost.parse("{G}"),
        subtypes = setOf(Subtype.ELF),
        power = 2,
        toughness = 2,
    )
    val registry = CardRegistry().also {
        it.register(stepWatcher)
        it.register(castWatcher)
        it.register(deathWatcher)
        it.register(creature)
    }
    val evaluator = PredicateEvaluator(registry)
    val detector = TriggerDetector(
        registry, predicateEvaluator = evaluator, conditionEvaluator = evaluator.conditions,
    )

    fun state(): GameState {
        val mode = OtariaState(
            players = players.zip(OtariaLocation.entries).associate { (id, location) ->
                id to OtariaPlayerState(location, location.position)
            },
            explorationsUsed = OtariaLocation.entries.associateWith { 0 },
            starPile = OtariaExplorationPile(emptyList()),
            colorPiles = Color.entries.associateWith { OtariaExplorationPile(emptyList()) },
            treasurePile = emptyList(),
            locationPile = emptyList(),
        )
        val entities = (players + actor).associateWith {
            ComponentContainer.of(PlayerComponent(it.value, 15), LifeTotalComponent(15), ManaPoolComponent())
        }
        var current = GameState(
            entities = entities,
            turnOrder = players,
            activePlayerId = actor,
            priorityPlayerId = actor,
            step = Step.UPKEEP,
            phase = Step.UPKEEP.phase,
            otaria = mode,
        )
        current = current.withEntity(primary, CardEntityFactory.create(creature, actor))
            .addToZone(ZoneKey(actor, Zone.BATTLEFIELD), primary)
        val encounter = OtariaEncounter(
            explorerId = players.first(),
            managerId = players[1],
            position = mode.players.getValue(players.first()).position,
            source = OtariaExplorationSource.Terrain(Color.GREEN),
            primaryId = primary,
            members = setOf(requireNotNull(current.objectRef(primary))),
        )
        return current.copy(otaria = mode.copy(encounters = mapOf(actor to encounter)))
    }

    fun putInZone(state: GameState, id: EntityId, definition: CardDefinition, owner: EntityId, zone: Zone) =
        state.withEntity(id, CardEntityFactory.create(definition, owner))
            .addToZone(ZoneKey(owner, zone), id)

    test("an encounter's graveyard step trigger is controlled by its actor") {
        val watcher = EntityId("step-watcher")
        val current = putInZone(state(), watcher, stepWatcher, actor, Zone.GRAVEYARD)
        val triggers = detector.detectPhaseStepTriggers(current, Step.UPKEEP, actor)
        triggers.filter { it.sourceId == watcher }.map { it.controllerId } shouldBe listOf(actor)
        current.zoneOwners shouldBe players + actor
        current.activePlayers shouldBe players
        current.copy(otaria = null).zoneOwners shouldBe players
    }

    test("an encounter's graveyard event trigger sees its own spell exactly once") {
        val watcher = EntityId("cast-watcher")
        val spell = EntityId("spell")
        val withWatcher = putInZone(state(), watcher, castWatcher, actor, Zone.GRAVEYARD)
        val current = putInZone(withWatcher, spell, creature, actor, Zone.HAND)
        val event = SpellCastEvent(spell, creature.name, actor)
        val triggers = detector.detectTriggers(current, listOf(event))
        triggers.filter { it.sourceId == watcher }.map { it.controllerId } shouldBe listOf(actor)
        detector.detectTriggers(current.copy(otaria = null), listOf(event))
            .filter { it.sourceId == watcher } shouldBe emptyList()
    }

    test("an encounter's graveyard batch trigger observes its own creature dying") {
        val watcher = EntityId("death-watcher")
        val dead = EntityId("dead-creature")
        val withWatcher = putInZone(state(), watcher, deathWatcher, actor, Zone.GRAVEYARD)
        val current = putInZone(withWatcher, dead, creature, actor, Zone.GRAVEYARD)
        val event = ZoneChangeEvent(dead, creature.name, Zone.BATTLEFIELD, Zone.GRAVEYARD, actor)
        val triggers = detector.detectTriggers(current, listOf(event))
        triggers.filter { it.sourceId == watcher }.map { it.controllerId } shouldBe listOf(actor)
    }
})
