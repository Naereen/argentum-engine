package com.wingedsheep.engine.otaria

import com.wingedsheep.engine.core.CardEntityFactory
import com.wingedsheep.engine.core.TurnFaceUpEvent
import com.wingedsheep.engine.event.TriggerDetector
import com.wingedsheep.engine.handlers.PredicateEvaluator
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.identity.FaceDownComponent
import com.wingedsheep.engine.state.components.identity.PlayerComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.ManaCost
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

class OtariaFaceUpTriggerTest : FunSpec({
    val explorer = EntityId("explorer")
    val nearbyPlayer = EntityId("nearby-player")
    val distantPlayer = EntityId("distant-player")
    val actor = EntityId("encounter")
    val primary = EntityId("encounter-primary")
    val sibling = EntityId("encounter-sibling")
    val ordinary = EntityId("ordinary-watcher")
    val filtered = EntityId("filtered-watcher")
    val attachment = EntityId("attachment")
    val other = EntityId("other-watcher")
    val nearby = EntityId("nearby-creature")
    val distant = EntityId("distant-creature")

    val watcher = card("Range-exempt face-up watcher") {
        typeLine = "Creature — Bird"
        power = 1
        toughness = 1
        triggeredAbility {
            trigger = Triggers.a().turnedFaceUp()
            effect = Effects.GainLife(1)
        }
    }
    val filteredWatcher = card("Filtered face-up watcher") {
        typeLine = "Creature — Bird"
        power = 1
        toughness = 1
        triggeredAbility {
            trigger = Triggers.anyPlayer.permanentTurnedFaceUp(GameObjectFilter.Creature)
            effect = Effects.GainLife(1)
        }
    }
    val otherWatcher = card("Other face-up watcher") {
        typeLine = "Creature — Bird"
        power = 1
        toughness = 1
        triggeredAbility {
            trigger = Triggers.another().turnedFaceUp()
            effect = Effects.GainLife(1)
        }
    }
    val equipment = card("Face-up watcher equipment") {
        typeLine = "Artifact — Equipment"
        triggeredAbility {
            trigger = Triggers.attached.turnedFaceUp()
            effect = Effects.GainLife(1)
        }
    }
    val creature = CardDefinition.creature(
        "Face-up target", ManaCost.ZERO, setOf(Subtype.HUMAN), 2, 2,
    )
    val registry = CardRegistry().also {
        listOf(watcher, filteredWatcher, otherWatcher, equipment, creature).forEach(it::register)
    }
    val predicates = PredicateEvaluator(registry)
    val detector = TriggerDetector(
        registry, predicateEvaluator = predicates, conditionEvaluator = predicates.conditions,
    )
    val seats = listOf(explorer, nearbyPlayer, distantPlayer)

    fun game(exempt: Boolean = true): GameState {
        val mode = OtariaState(
            players = mapOf(
                explorer to OtariaPlayerState(OtariaLocation.entries[0], OtariaPosition(6, 5)),
                nearbyPlayer to OtariaPlayerState(OtariaLocation.entries[1], OtariaPosition(7, 5)),
                distantPlayer to OtariaPlayerState(OtariaLocation.entries[2], OtariaPosition(10, 11)),
            ),
            explorationsUsed = OtariaLocation.entries.associateWith { 0 },
            starPile = OtariaExplorationPile(emptyList()),
            colorPiles = Color.entries.associateWith { OtariaExplorationPile(emptyList()) },
            treasurePile = emptyList(),
            locationPile = emptyList(),
            encounterSupport = OtariaEncounterSupportTable(
                colors = mapOf(Color.WHITE to mapOf(watcher.name to OtariaEncounterSupport(
                    faceUpTriggerIgnoresRange = exempt,
                ))),
            ),
        )
        var current = GameState(
            entities = (seats + actor).associateWith {
                ComponentContainer.of(PlayerComponent(it.value), ManaPoolComponent())
            },
            turnOrder = seats,
            activePlayerId = explorer,
            priorityPlayerId = explorer,
            otaria = mode,
        )
        fun put(id: EntityId, owner: EntityId, definition: CardDefinition) {
            current = current.withEntity(id, CardEntityFactory.create(definition, owner))
                .addToZone(ZoneKey(owner, Zone.BATTLEFIELD), id)
        }
        put(primary, actor, watcher)
        put(sibling, actor, watcher)
        put(ordinary, explorer, watcher)
        put(filtered, explorer, filteredWatcher)
        put(other, explorer, otherWatcher)
        put(nearby, nearbyPlayer, creature)
        put(distant, distantPlayer, creature)
        put(attachment, explorer, equipment)
        current = current.updateEntity(attachment) { it.with(AttachedToComponent(distant)) }
        val encounter = OtariaEncounter(
            explorerId = explorer,
            managerId = nearbyPlayer,
            position = OtariaPosition(6, 5),
            source = OtariaExplorationSource.Terrain(Color.WHITE),
            primaryId = primary,
            members = setOf(requireNotNull(current.objectRef(primary)), requireNotNull(current.objectRef(sibling))),
        )
        return current.copy(otaria = mode.copy(encounters = mapOf(actor to encounter)))
    }

    fun sources(state: GameState, target: EntityId, controller: EntityId): Set<EntityId> =
        detector.detectTriggers(state, listOf(TurnFaceUpEvent(target, creature.name, controller)))
            .map { it.sourceId }.toSet()

    test("only the designated encounter watcher observes distant face-up events") {
        val current = game()
        sources(current, distant, distantPlayer) shouldBe setOf(primary)
        sources(current, nearby, nearbyPlayer) shouldBe setOf(primary, sibling, ordinary, filtered, other)
        sources(game(exempt = false), distant, distantPlayer) shouldBe emptySet()
    }

    test("the exception belongs to the current primary object under encounter control") {
        val current = game()
        val battlefield = ZoneKey(actor, Zone.BATTLEFIELD)
        val graveyard = ZoneKey(actor, Zone.GRAVEYARD)
        val returned = current.moveToZone(primary, battlefield, graveyard)
            .moveToZone(primary, graveyard, battlefield)
        sources(returned, distant, distantPlayer) shouldBe emptySet()

        val stolen = current.updateEntity(primary) { it.with(ControllerComponent(explorer)) }
        sources(stolen, distant, distantPlayer) shouldBe emptySet()

        val faceDown = current.updateEntity(primary) { it.with(FaceDownComponent) }
        sources(faceDown, distant, distantPlayer) shouldBe emptySet()
    }

    test("attachment triggers observe the same range and ordinary Magic has no spatial restriction") {
        val current = game(exempt = false)
        sources(current, distant, distantPlayer) shouldBe emptySet()
        val mode = requireNotNull(current.otaria)
        val closer = current.copy(otaria = mode.copy(players = mode.players + (
            distantPlayer to mode.players.getValue(distantPlayer).copy(position = OtariaPosition(7, 5))
        )))
        sources(closer, distant, distantPlayer) shouldBe setOf(primary, sibling, ordinary, filtered, other, attachment)
        val ordinaryMagic = sources(current.copy(otaria = null), distant, distantPlayer)
        (ordinary in ordinaryMagic) shouldBe true
        (attachment in ordinaryMagic) shouldBe true
    }

    test("another permanent excludes the watcher itself in both modes") {
        val current = game()
        (other in sources(current, other, explorer)) shouldBe false
        (other in sources(current.copy(otaria = null), other, explorer)) shouldBe false
        (other in sources(current, nearby, nearbyPlayer)) shouldBe true
    }
})
