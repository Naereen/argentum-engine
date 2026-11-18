package com.wingedsheep.engine.otaria

import com.wingedsheep.engine.core.CardEntityFactory
import com.wingedsheep.engine.core.EngineServices
import com.wingedsheep.engine.event.GrantedStaticAbility
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.PredicateContext
import com.wingedsheep.engine.handlers.TargetingSourceType
import com.wingedsheep.engine.handlers.effects.TargetResolutionUtils
import com.wingedsheep.engine.handlers.effects.BattlefieldFilterUtils
import com.wingedsheep.engine.legalactions.utils.TargetEnumerationUtils
import com.wingedsheep.engine.mechanics.combat.CombatDefenders
import com.wingedsheep.engine.mechanics.layers.AffectsFilter
import com.wingedsheep.engine.mechanics.layers.ContinuousEffectData
import com.wingedsheep.engine.mechanics.layers.ContinuousEffectSourceComponent
import com.wingedsheep.engine.mechanics.layers.Layer
import com.wingedsheep.engine.mechanics.layers.Modification
import com.wingedsheep.engine.mechanics.layers.SerializableModification
import com.wingedsheep.engine.mechanics.layers.addFloatingEffect
import com.wingedsheep.engine.mechanics.stack.ResolutionTargetValidator
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.CantBeBlockedWhilePropertyAtMostComponent
import com.wingedsheep.engine.state.components.battlefield.CastChoicesComponent
import com.wingedsheep.engine.state.components.battlefield.ChoiceValue
import com.wingedsheep.engine.state.components.battlefield.TimestampComponent
import com.wingedsheep.engine.state.components.identity.ChangesAllColorWordsComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.identity.LifeTotalComponent
import com.wingedsheep.engine.state.components.identity.PlayerComponent
import com.wingedsheep.engine.state.components.identity.ProtectionComponent
import com.wingedsheep.engine.state.components.identity.TextChanges
import com.wingedsheep.engine.state.components.identity.TextReplacement
import com.wingedsheep.engine.state.components.identity.TextReplacementCategory
import com.wingedsheep.engine.state.components.identity.TextReplacementComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.state.components.stack.SpellOnStackComponent
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.AbilityFlag
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.core.Supertype
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Targets
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.CanAttackAsThoughHasty
import com.wingedsheep.sdk.scripting.ChoiceSlot
import com.wingedsheep.sdk.scripting.Duration
import com.wingedsheep.sdk.scripting.filters.unified.GroupFilter
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter
import com.wingedsheep.sdk.scripting.targets.TargetObject
import com.wingedsheep.sdk.scripting.effects.DealDamageEffect
import com.wingedsheep.sdk.scripting.references.Player
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldContain
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class OtariaRangeTest : FunSpec({
    val players = (1..3).map { EntityId("player-$it") }
    val (active, near, far) = players
    val positions = listOf(OtariaPosition(6, 5), OtariaPosition(7, 5), OtariaPosition(10, 11))
    val creature = CardDefinition.creature("Range Test Creature", ManaCost.ZERO, setOf(Subtype.HUMAN), 2, 2)
    val spell = CardDefinition.instant("Range Test Spell", ManaCost.ZERO, "")
    val registry = CardRegistry().also { it.register(creature); it.register(spell) }
    val services = EngineServices(registry)

    fun game(): GameState {
        val mode = OtariaState(
            players = players.indices.associate { index ->
                players[index] to OtariaPlayerState(OtariaLocation.entries[index], positions[index])
            },
            explorationsUsed = OtariaLocation.entries.associateWith { 0 },
            starPile = OtariaExplorationPile(emptyList()),
            colorPiles = Color.entries.associateWith { OtariaExplorationPile(emptyList()) },
            treasurePile = emptyList(),
            locationPile = emptyList(),
        )
        return GameState(
            entities = players.associateWith {
                ComponentContainer.of(PlayerComponent(it.value, 15), LifeTotalComponent(15), ManaPoolComponent())
            },
            turnOrder = players,
            activePlayerId = active,
            priorityPlayerId = active,
            turnNumber = 1,
            step = Step.DECLARE_ATTACKERS,
            phase = Step.DECLARE_ATTACKERS.phase,
            otaria = mode,
        )
    }

    fun card(state: GameState, id: EntityId, owner: EntityId, zone: Zone): GameState =
        state.withEntity(id, CardEntityFactory.create(creature, owner)).addToZone(ZoneKey(owner, zone), id)

    fun colorWordSource(state: GameState, id: EntityId, owner: EntityId, chosen: Color = Color.BLUE): GameState =
        card(state, id, owner, Zone.BATTLEFIELD).updateEntity(id) {
            it.with(ChangesAllColorWordsComponent)
                .with(CastChoicesComponent(chosen = mapOf(ChoiceSlot.COLOR to ChoiceValue.ColorChoice(chosen))))
        }

    fun coloredCreature(state: GameState, id: EntityId, owner: EntityId, color: Color): GameState {
        val mana = when (color) {
            Color.RED -> "{R}"
            Color.BLUE -> "{U}"
            else -> error("The test only supplies red and blue creatures")
        }
        val definition = CardDefinition.creature(
            "Range ${color.name}", ManaCost.parse(mana), setOf(Subtype.HUMAN), 2, 2,
        )
        return state.withEntity(id, CardEntityFactory.create(definition, owner))
            .addToZone(ZoneKey(owner, Zone.BATTLEFIELD), id)
    }

    test("target offers and validation include the same space and adjacent space only") {
        val state = game()
        val finder = services.targetFinder.findLegalTargets(state, Targets.Player, active)
        val enumerated = TargetEnumerationUtils(services.predicateEvaluator).findValidTargets(state, active, Targets.Player)
        finder.shouldContainExactlyInAnyOrder(active, near)
        enumerated.shouldContainExactlyInAnyOrder(active, near)

        fun validate(target: EntityId) = services.targetValidator.validateTargets(
            state = state,
            targets = listOf(ChosenTarget.Player(target)),
            requirements = listOf(Targets.Player),
            casterId = active,
            targetingSourceType = TargetingSourceType.SPELL,
        )
        validate(active) shouldBe null
        validate(near) shouldBe null
        validate(far) shouldBe "Target is outside Otaria range"
        services.targetFinder.findLegalTargets(state.copy(otaria = null), Targets.Player, active)
            .shouldContainExactlyInAnyOrder(players)

        val mode = requireNotNull(state.otaria)
        val twoAway = state.copy(otaria = mode.copy(players = mode.players + (
            near to mode.players.getValue(near).copy(position = OtariaPosition(6, 7))
        )))
        services.targetFinder.findLegalTargets(twoAway, Targets.Player, active)
            .shouldContainExactlyInAnyOrder(active)
        TargetEnumerationUtils(services.predicateEvaluator).findValidTargets(twoAway, active, Targets.Player)
            .shouldContainExactlyInAnyOrder(active)
        services.targetValidator.validateTargets(
            state = twoAway,
            targets = listOf(ChosenTarget.Player(near)),
            requirements = listOf(Targets.Player),
            casterId = active,
            targetingSourceType = TargetingSourceType.SPELL,
        ) shouldBe "Target is outside Otaria range"
    }

    test("opponent targets and each-opponent effects reach opponents up to two hexes away") {
        val initial = game()
        val mode = requireNotNull(initial.otaria)
        val state = initial.copy(
            otaria = mode.copy(
                players = mode.players +
                    (near to mode.players.getValue(near).copy(position = OtariaPosition(6, 7))) +
                    (far to mode.players.getValue(far).copy(position = OtariaPosition(6, 8))),
            ),
        )

        services.targetFinder.findLegalTargets(state, Targets.Opponent, active)
            .shouldContainExactlyInAnyOrder(near)
        services.targetFinder.findLegalTargets(state, Targets.OpponentOrPlaneswalker, active)
            .shouldContainExactlyInAnyOrder(near)
        TargetEnumerationUtils(services.predicateEvaluator)
            .findValidTargets(state, active, Targets.Opponent)
            .shouldContainExactlyInAnyOrder(near)
        TargetEnumerationUtils(services.predicateEvaluator)
            .findValidTargets(state, active, Targets.OpponentOrPlaneswalker)
            .shouldContainExactlyInAnyOrder(near)
        services.targetValidator.validateTargets(
            state = state,
            targets = listOf(ChosenTarget.Player(near)),
            requirements = listOf(Targets.Opponent),
            casterId = active,
            targetingSourceType = TargetingSourceType.SPELL,
        ) shouldBe null
        services.targetValidator.validateTargets(
            state = state,
            targets = listOf(ChosenTarget.Player(far)),
            requirements = listOf(Targets.Opponent),
            casterId = active,
            targetingSourceType = TargetingSourceType.SPELL,
        ) shouldBe "Target is outside Otaria range"
        val opponentTarget = ChosenTarget.Player(near)
        ResolutionTargetValidator(services.predicateEvaluator).validateTargets(
            state = state,
            targets = listOf(opponentTarget),
            controllerId = active,
            targetRequirements = listOf(Targets.Opponent),
            targetingSourceType = TargetingSourceType.SPELL,
        ) shouldBe listOf(opponentTarget)
        ResolutionTargetValidator(services.predicateEvaluator).validateTargets(
            state = state,
            targets = listOf(ChosenTarget.Player(far)),
            controllerId = active,
            targetRequirements = listOf(Targets.Opponent),
            targetingSourceType = TargetingSourceType.SPELL,
        ) shouldBe emptyList()

        val context = EffectContext(sourceId = null, controllerId = active)
        TargetResolutionUtils.resolvePlayerTargets(
            EffectTarget.PlayerRef(Player.EachOpponent), state, context,
        ).shouldContainExactlyInAnyOrder(near)

        val damage = services.effectExecutorRegistry.execute(
            state,
            DealDamageEffect(1, EffectTarget.PlayerRef(Player.EachOpponent)),
            context,
        )
        damage.state.getEntity(near)?.get<LifeTotalComponent>()?.life shouldBe 14
        damage.state.getEntity(far)?.get<LifeTotalComponent>()?.life shouldBe 15
    }

    test("legendary encounter creatures substitute for player and opponent targets") {
        val initial = game()
        val actor = EntityId("legendary-encounter")
        val creatureId = EntityId("legendary-creature")
        val legendary = CardDefinition.creature(
            "Legendary Encounter",
            ManaCost.ZERO,
            setOf(Subtype.HUMAN),
            3,
            3,
            supertypes = setOf(Supertype.LEGENDARY),
        )
        val withCreature = initial
            .withEntity(actor, ComponentContainer.of(PlayerComponent("Encounter")))
            .withEntity(creatureId, CardEntityFactory.create(legendary, actor))
            .addToZone(ZoneKey(actor, Zone.BATTLEFIELD), creatureId)
        val encounter = OtariaEncounter(
            explorerId = active,
            managerId = far,
            position = positions[1],
            source = OtariaExplorationSource.Terrain(Color.GREEN),
            primaryId = creatureId,
            members = setOf(requireNotNull(withCreature.objectRef(creatureId))),
        )
        val state = withCreature.copy(
            otaria = requireNotNull(withCreature.otaria).copy(
                encounters = mapOf(actor to encounter),
            ),
        )
        val permanent = ChosenTarget.Permanent(creatureId)
        OtariaTargeting.isLegendaryEncounterCreature(state, active, creatureId) shouldBe true
        OtariaRange.contains(state, active, creatureId) shouldBe true

        for (requirement in listOf(
            Targets.Player,
            Targets.Opponent,
            Targets.CreatureOrPlayer,
            Targets.PlayerOrPlaneswalker,
            Targets.OpponentOrPlaneswalker,
            Targets.OpponentOrTheirCreatureOrPlaneswalker,
        )) {
            withClue(requirement.description) {
                services.targetFinder.findLegalTargets(state, requirement, active) shouldContain creatureId
                services.targetValidator.validateTargets(
                    state = state,
                    targets = listOf(permanent),
                    requirements = listOf(requirement),
                    casterId = active,
                    targetingSourceType = TargetingSourceType.SPELL,
                ) shouldBe null
            }
        }
    }

    test("permanent range follows projected control and cards in zones follow their zone owner") {
        val nearOwned = EntityId("near-owned")
        val farOwned = EntityId("far-owned")
        val farGraveyard = EntityId("far-graveyard")
        val state = card(card(card(game(), nearOwned, near, Zone.BATTLEFIELD), farOwned, far, Zone.BATTLEFIELD),
            farGraveyard, far, Zone.GRAVEYARD)
            .updateEntity(nearOwned) { it.with(ControllerComponent(far)) }
            .updateEntity(farOwned) { it.with(ControllerComponent(near)) }
        val requirement = TargetObject(filter = TargetFilter.Creature)

        OtariaRange.contains(state, active, nearOwned) shouldBe false
        OtariaRange.contains(state, active, farOwned) shouldBe true
        OtariaRange.contains(state, active, farGraveyard) shouldBe false
        services.targetFinder.findLegalTargets(state, requirement, active).shouldContainExactlyInAnyOrder(farOwned)
        TargetEnumerationUtils(services.predicateEvaluator).findValidTargets(state, active, requirement)
            .shouldContainExactlyInAnyOrder(farOwned)
        val farTarget = ChosenTarget.Permanent(nearOwned)
        services.targetValidator.validateTargets(
            state, listOf(farTarget), listOf(requirement), active, targetingSourceType = TargetingSourceType.SPELL,
        ) shouldBe "Target is outside Otaria range"

        val mode = requireNotNull(state.otaria)
        val twoAway = state.copy(otaria = mode.copy(players = mode.players + (
            near to mode.players.getValue(near).copy(position = OtariaPosition(6, 7))
        )))
        OtariaRange.contains(twoAway, active, farOwned) shouldBe false
        services.targetFinder.findLegalTargets(twoAway, requirement, active) shouldBe emptyList()
        services.targetValidator.validateTargets(
            twoAway,
            listOf(ChosenTarget.Permanent(farOwned)),
            listOf(requirement),
            active,
            targetingSourceType = TargetingSourceType.SPELL,
        ) shouldBe "Target is outside Otaria range"
    }

    test("stack range follows the spell controller rather than its owner") {
        val id = EntityId("spell")
        val state = game().withEntity(id, CardEntityFactory.create(spell, near).with(SpellOnStackComponent(far)))
            .pushToStack(id)
        OtariaRange.contains(state, active, id) shouldBe false
        val controlledNearby = state.updateEntity(id) { it.with(SpellOnStackComponent(near)) }
        OtariaRange.contains(controlledNearby, active, id) shouldBe true
    }

    test("an encounter's fixed position is independent of its human manager") {
        val actor = EntityId("encounter-actor")
        val creatureId = EntityId("encounter-creature")
        val initial = game()
        val mode = requireNotNull(initial.otaria)
        val encounter = OtariaEncounter(
            explorerId = active,
            managerId = far,
            position = positions[1],
            source = OtariaExplorationSource.Terrain(Color.GREEN),
            primaryId = creatureId,
        )
        val state = card(initial.copy(otaria = mode.copy(encounters = mapOf(actor to encounter))),
            creatureId, actor, Zone.BATTLEFIELD)
            .withEntity(actor, ComponentContainer.of(PlayerComponent("Encounter")))
        state.actorFor(actor) shouldBe far
        state.activePlayers shouldBe players
        OtariaRange.contains(state, active, actor) shouldBe true
        OtariaRange.contains(state, active, creatureId) shouldBe true
        OtariaRange.contains(state, far, creatureId) shouldBe false

        val managerMoved = state.copy(otaria = requireNotNull(state.otaria).let { current ->
            current.copy(players = current.players + (far to current.players.getValue(far).copy(position = positions[0])))
        })
        OtariaRange.contains(managerMoved, active, creatureId) shouldBe true
        val stolen = state.updateEntity(creatureId) { it.with(ControllerComponent(far)) }
        OtariaRange.contains(stolen, active, creatureId) shouldBe false
    }

    test("a target that moves out of range becomes illegal at resolution") {
        val state = game()
        val target = ChosenTarget.Player(near)
        val validator = ResolutionTargetValidator(services.predicateEvaluator)
        fun valid(current: GameState) = validator.validateTargets(
            current, listOf(target), controllerId = active, targetRequirements = listOf(Targets.Player),
            targetingSourceType = TargetingSourceType.SPELL,
        )
        valid(state) shouldBe listOf(target)
        val mode = requireNotNull(state.otaria)
        val moved = state.copy(otaria = mode.copy(players = mode.players + (
            near to mode.players.getValue(near).copy(position = positions[2])
        )))
        valid(moved) shouldBe emptyList()
    }

    test("combat reaches only the same and adjacent spaces") {
        val attacker = EntityId("attacker")
        val initial = card(game(), attacker, active, Zone.BATTLEFIELD)
        val mode = requireNotNull(initial.otaria)
        fun position(state: GameState, player: EntityId, position: OtariaPosition): GameState {
            val current = requireNotNull(state.otaria)
            return state.copy(otaria = current.copy(players = current.players +
               (player to current.players.getValue(player).copy(position = position))))
        }
        val twoAway = position(initial, near, OtariaPosition(6, 7))
        val sameSpace = position(twoAway, far, mode.players.getValue(active).position)

        OtariaBoard.distance(mode.players.getValue(active).position, OtariaPosition(6, 7)) shouldBe 2L
        val adjacentOffered = services.legalActionEnumerator.enumerate(initial, active)
            .single { it.actionType == "DeclareAttackers" }
        adjacentOffered.validAttackTargets.orEmpty() shouldBe listOf(near)
        val twoAwayOffered = services.legalActionEnumerator.enumerate(twoAway, active)
            .single { it.actionType == "DeclareAttackers" }
        twoAwayOffered.validAttackTargets.orEmpty() shouldBe emptyList()
        services.combatManager.legalDefendersFor(twoAway, attacker, active, listOf(near, far)) shouldBe emptyList()
        CombatDefenders.isLegalEnteringAttackTarget(twoAway, active, near) shouldBe false
        services.combatManager.declareAttackers(twoAway, active, mapOf(attacker to near)).error shouldNotBe null

        val sameSpaceOffered = services.legalActionEnumerator.enumerate(sameSpace, active)
            .single { it.actionType == "DeclareAttackers" }
        sameSpaceOffered.validAttackTargets.orEmpty() shouldBe listOf(far)
        services.combatManager.declareAttackers(sameSpace, active, mapOf(attacker to far)).error shouldBe null
        services.combatManager.legalDefendersFor(twoAway.copy(otaria = null), attacker, active, listOf(near, far))
            .shouldContainExactlyInAnyOrder(near, far)
    }

    test("combat range does not extend with movement reach") {
        val attacker = EntityId("attacker")
        val initial = card(game(), attacker, active, Zone.BATTLEFIELD)
        val mode = requireNotNull(initial.otaria)
        val twoAway = initial.copy(otaria = mode.copy(players = mode.players +
            (near to mode.players.getValue(near).copy(position = OtariaPosition(6, 7)))))
        val nine = (1..8).fold(twoAway) { state, index ->
            card(state, EntityId("owned-$index"), active, Zone.BATTLEFIELD)
        }
        val stolen = EntityId("stolen")
        val ten = card(nine, stolen, far, Zone.BATTLEFIELD)
            .updateEntity(stolen) { it.with(ControllerComponent(active)) }

        fun offered(state: GameState) = services.legalActionEnumerator.enumerate(state, active)
            .single { it.actionType == "DeclareAttackers" }.validAttackTargets.orEmpty()
        offered(nine) shouldBe emptyList()
        services.combatManager.declareAttackers(nine, active, mapOf(attacker to near)).error shouldNotBe null
        offered(ten) shouldBe emptyList()
        CombatDefenders.isLegalEnteringAttackTarget(ten, active, near) shouldBe false
        services.combatManager.declareAttackers(ten, active, mapOf(attacker to near)).error shouldNotBe null
        val tenMode = requireNotNull(ten.otaria)
        val adjacent = ten.copy(otaria = tenMode.copy(players =
            tenMode.players + (near to tenMode.players.getValue(near).copy(position = OtariaPosition(6, 6)))))
        offered(adjacent) shouldBe listOf(near)
        services.combatManager.declareAttackers(adjacent, active, mapOf(attacker to near)).error shouldBe null
        val lostControl = ten.updateEntity(stolen) { it.with(ControllerComponent(far)) }
        offered(lostControl) shouldBe emptyList()
    }

    test("battlefield group selection only sees permanents in range") {
        val own = EntityId("own")
        val nearby = EntityId("nearby")
        val distant = EntityId("distant")
        val state = card(card(card(game(), own, active, Zone.BATTLEFIELD), nearby, near, Zone.BATTLEFIELD),
            distant, far, Zone.BATTLEFIELD)
        fun matching(current: GameState) = BattlefieldFilterUtils.findMatchingOnBattlefield(
            current, GameObjectFilter.Creature, PredicateContext(controllerId = active),
            predicateEvaluator = services.predicateEvaluator,
        )
        matching(state).shouldContainExactlyInAnyOrder(own, nearby)
        matching(state.copy(otaria = null)).shouldContainExactlyInAnyOrder(own, nearby, distant)
    }

    test("continuous battlefield effects follow positions and projected controllers") {
        val source = EntityId("source")
        val nearby = EntityId("nearby")
        val distant = EntityId("distant")
        val state = card(card(card(game(), source, active, Zone.BATTLEFIELD), nearby, near, Zone.BATTLEFIELD),
            distant, far, Zone.BATTLEFIELD)
            .updateEntity(source) { entity ->
                entity.with(ContinuousEffectSourceComponent(listOf(ContinuousEffectData(
                    modification = Modification.ModifyPowerToughness(powerMod = 1, toughnessMod = 1),
                    affectsFilter = AffectsFilter.AllCreatures,
                ))))
            }
        state.projectedState.getPower(source) shouldBe 3
        state.projectedState.getPower(nearby) shouldBe 3
        state.projectedState.getPower(distant) shouldBe 2
        state.copy(otaria = null).projectedState.getPower(distant) shouldBe 3

        val mode = requireNotNull(state.otaria)
        val moved = state.copy(otaria = mode.copy(players = mode.players + (
            near to mode.players.getValue(near).copy(position = positions[2])
        )))
        moved.projectedState.getPower(nearby) shouldBe 2
        val changedControl = state.updateEntity(distant) { it.with(ControllerComponent(near)) }
        changedControl.projectedState.getPower(distant) shouldBe 3
    }

    test("a continuous effect can reach a nearby spell on the stack") {
        val source = EntityId("color-source")
        val spellId = EntityId("near-spell")
        val state = card(game(), source, active, Zone.BATTLEFIELD)
            .withEntity(spellId, CardEntityFactory.create(spell, far).with(SpellOnStackComponent(near)))
            .pushToStack(spellId)
            .addFloatingEffect(
                layer = Layer.COLOR,
                modification = SerializableModification.ChangeColor(setOf(Color.BLUE.name)),
                affectedEntities = setOf(spellId),
                duration = Duration.EndOfTurn,
                context = EffectContext(source, active),
            )
        state.projectedState.colorsOf(spellId) shouldBe setOf(Color.BLUE.name)
        val outOfRange = state.updateEntity(spellId) { it.with(SpellOnStackComponent(far)) }
        outOfRange.projectedState.colorsOf(spellId) shouldBe emptySet()
        outOfRange.copy(otaria = null).projectedState.colorsOf(spellId) shouldBe setOf(Color.BLUE.name)
    }

    test("post-layer combat permissions respect range for static and granted abilities") {
        val source = EntityId("permission-source")
        val nearby = EntityId("permission-near")
        val distant = EntityId("permission-far")
        val state = card(card(card(game(), source, active, Zone.BATTLEFIELD), nearby, near, Zone.BATTLEFIELD),
            distant, far, Zone.BATTLEFIELD)
            .updateEntity(source) { it.with(CantBeBlockedWhilePropertyAtMostComponent(
                maxValue = 2,
                checkPower = true,
                checkToughness = false,
                affects = AffectsFilter.AllCreatures,
            )) }
            .copy(grantedStaticAbilities = listOf(GrantedStaticAbility(
                source, CanAttackAsThoughHasty(GroupFilter.AllCreatures), Duration.EndOfTurn,
            )))
        for (id in listOf(source, nearby)) {
            state.projectedState.hasKeyword(id, AbilityFlag.CANT_BE_BLOCKED) shouldBe true
            state.projectedState.canAttackAsThoughHasty(id) shouldBe true
        }
        state.projectedState.hasKeyword(distant, AbilityFlag.CANT_BE_BLOCKED) shouldBe false
        state.projectedState.canAttackAsThoughHasty(distant) shouldBe false
        state.copy(otaria = null).projectedState.hasKeyword(distant, AbilityFlag.CANT_BE_BLOCKED) shouldBe true
        state.copy(otaria = null).projectedState.canAttackAsThoughHasty(distant) shouldBe true
    }

    test("a greatest-mana-value continuous filter compares only creatures in range") {
        val source = EntityId("comparison-source")
        val nearby = EntityId("comparison-near")
        val distant = EntityId("comparison-far")
        val nearCard = CardDefinition.creature(
            "Nearby contender", ManaCost.parse("{2}"), setOf(Subtype.HUMAN), 2, 2,
        )
        val farCard = CardDefinition.creature(
            "Distant contender", ManaCost.parse("{9}"), setOf(Subtype.HUMAN), 2, 2,
        )
        val state = card(game(), source, active, Zone.BATTLEFIELD)
            .withEntity(nearby, CardEntityFactory.create(nearCard, near))
            .addToZone(ZoneKey(near, Zone.BATTLEFIELD), nearby)
            .withEntity(distant, CardEntityFactory.create(farCard, far))
            .addToZone(ZoneKey(far, Zone.BATTLEFIELD), distant)
            .updateEntity(source) { entity ->
                entity.with(ContinuousEffectSourceComponent(listOf(ContinuousEffectData(
                    modification = Modification.GrantKeyword(AbilityFlag.CANT_BE_BLOCKED.name),
                    affectsFilter = AffectsFilter.Generic(GroupFilter(
                        GameObjectFilter.Creature.hasGreatestManaValueAmongAllCreatures(),
                    )),
                ))))
            }
        state.projectedState.hasKeyword(source, AbilityFlag.CANT_BE_BLOCKED) shouldBe false
        state.projectedState.hasKeyword(nearby, AbilityFlag.CANT_BE_BLOCKED) shouldBe true
        state.projectedState.hasKeyword(distant, AbilityFlag.CANT_BE_BLOCKED) shouldBe false
        state.copy(otaria = null).projectedState.hasKeyword(nearby, AbilityFlag.CANT_BE_BLOCKED) shouldBe false
        state.copy(otaria = null).projectedState.hasKeyword(distant, AbilityFlag.CANT_BE_BLOCKED) shouldBe true
    }

    test("a subtype-based static effect sees a permanent moved into range by a control effect") {
        val source = EntityId("subtype-source")
        val distant = EntityId("stolen-creature")
        val state = card(card(game(), source, active, Zone.BATTLEFIELD), distant, far, Zone.BATTLEFIELD)
            .updateEntity(source) { entity ->
                entity.with(ContinuousEffectSourceComponent(listOf(ContinuousEffectData(
                    modification = Modification.GrantKeyword(AbilityFlag.CANT_BE_BLOCKED.name),
                    affectsFilter = AffectsFilter.WithSubtype(Subtype.HUMAN.value),
                ))))
            }
            .addFloatingEffect(
                layer = Layer.CONTROL,
                modification = SerializableModification.ChangeController(near),
                affectedEntities = setOf(distant),
                duration = Duration.EndOfTurn,
                context = EffectContext(distant, far),
            )
        state.projectedState.getController(distant) shouldBe near
        state.projectedState.hasKeyword(distant, AbilityFlag.CANT_BE_BLOCKED) shouldBe true
    }

    test("a subtype-based static effect stops reaching a permanent moved out of range") {
        val source = EntityId("subtype-source")
        val nearby = EntityId("stolen-creature")
        val state = card(card(game(), source, active, Zone.BATTLEFIELD), nearby, near, Zone.BATTLEFIELD)
            .updateEntity(source) { entity ->
                entity.with(ContinuousEffectSourceComponent(listOf(ContinuousEffectData(
                    modification = Modification.GrantKeyword(AbilityFlag.CANT_BE_BLOCKED.name),
                    affectsFilter = AffectsFilter.WithSubtype(Subtype.HUMAN.value),
                ))))
            }
            .addFloatingEffect(
                layer = Layer.CONTROL,
                modification = SerializableModification.ChangeController(far),
                affectedEntities = setOf(nearby),
                duration = Duration.EndOfTurn,
                context = EffectContext(nearby, near),
            )
        state.projectedState.getController(nearby) shouldBe far
        state.projectedState.hasKeyword(nearby, AbilityFlag.CANT_BE_BLOCKED) shouldBe false
    }

    test("later control effects re-evaluate range after an earlier control change") {
        val source = EntityId("control-source")
        val distant = EntityId("stolen-creature")
        val state = card(card(game(), source, active, Zone.BATTLEFIELD), distant, far, Zone.BATTLEFIELD)
            .updateEntity(source) { entity ->
                entity.with(TimestampComponent(2L))
                    .with(ContinuousEffectSourceComponent(listOf(ContinuousEffectData(
                        modification = Modification.ChangeController(active),
                        affectsFilter = AffectsFilter.WithSubtype(Subtype.HUMAN.value),
                    ))))
            }
            .addFloatingEffect(
                layer = Layer.CONTROL,
                modification = SerializableModification.ChangeController(near),
                affectedEntities = setOf(distant),
                duration = Duration.EndOfTurn,
                context = EffectContext(distant, far),
                timestamp = 1L,
            )
        state.projectedState.getController(distant) shouldBe active
    }

    test("global color-word changes reach nearby objects and follow a spell's caster") {
        val source = EntityId("word-source")
        val nearby = EntityId("near-text")
        val distant = EntityId("far-text")
        val borrowedSpell = EntityId("borrowed-spell")
        val state = card(card(colorWordSource(game(), source, active), nearby, near, Zone.BATTLEFIELD),
            distant, far, Zone.BATTLEFIELD)
            .withEntity(borrowedSpell, CardEntityFactory.create(spell, far))
            .addToZone(ZoneKey(far, Zone.HAND), borrowedSpell)

        TextChanges.of(state, nearby)?.replaceColor(Color.RED) shouldBe Color.BLUE
        TextChanges.of(state, distant) shouldBe null
        TextChanges.forSpell(state, borrowedSpell, active)?.replaceColor(Color.RED) shouldBe Color.BLUE
        TextChanges.forSpell(state, borrowedSpell, far) shouldBe null
        TextChanges.of(state.copy(otaria = null), distant)?.replaceColor(Color.RED) shouldBe Color.BLUE
    }

    test("only in-range color-word sources chain in timestamp order before an object's own changes") {
        val first = EntityId("first-words")
        val second = EntityId("second-words")
        val distant = EntityId("distant-words")
        val target = EntityId("changed-text")
        val state = card(colorWordSource(
            colorWordSource(colorWordSource(game(), first, active), second, near, Color.GREEN),
            distant, far, Color.RED,
        ), target, active, Zone.BATTLEFIELD)
            .updateEntity(first) { it.with(TimestampComponent(1L)) }
            .updateEntity(second) { it.with(TimestampComponent(2L)) }
            .updateEntity(distant) { it.with(TimestampComponent(3L)) }
            .updateEntity(target) { it.with(TextReplacementComponent(listOf(
                TextReplacement("Green", "White", TextReplacementCategory.COLOR_WORD),
            ))) }
        TextChanges.of(state, target)?.replaceColor(Color.RED) shouldBe Color.WHITE
        TextChanges.of(state.copy(otaria = null), target)?.replaceColor(Color.BLUE) shouldBe Color.RED
    }

    test("color-word changes and static filters follow a source moved out of range") {
        val words = EntityId("moving-words")
        val source = EntityId("filtered-lord")
        val red = EntityId("red-subject")
        val blue = EntityId("blue-subject")
        val initial = coloredCreature(
            coloredCreature(card(colorWordSource(game(), words, near), source, active, Zone.BATTLEFIELD),
                red, active, Color.RED),
            blue, active, Color.BLUE,
        ).updateEntity(source) {
            it.with(ContinuousEffectSourceComponent(listOf(ContinuousEffectData(
                modification = Modification.GrantKeyword(AbilityFlag.CANT_BE_BLOCKED.name),
                affectsFilter = AffectsFilter.Generic(GroupFilter(GameObjectFilter.Creature.withColor(Color.RED))),
            ))))
        }
        initial.projectedState.hasKeyword(red, AbilityFlag.CANT_BE_BLOCKED) shouldBe false
        initial.projectedState.hasKeyword(blue, AbilityFlag.CANT_BE_BLOCKED) shouldBe true

        val moved = initial.addFloatingEffect(
            layer = Layer.CONTROL,
            modification = SerializableModification.ChangeController(far),
            affectedEntities = setOf(words),
            duration = Duration.EndOfTurn,
            context = EffectContext(words, near),
        )
        moved.projectedState.getController(words) shouldBe far
        moved.projectedState.hasKeyword(red, AbilityFlag.CANT_BE_BLOCKED) shouldBe true
        moved.projectedState.hasKeyword(blue, AbilityFlag.CANT_BE_BLOCKED) shouldBe false
        moved.copy(otaria = null).projectedState.hasKeyword(blue, AbilityFlag.CANT_BE_BLOCKED) shouldBe true
    }

    test("a later control filter reads color words after an earlier source control change") {
        val words = EntityId("moving-control-words")
        val source = EntityId("color-control-source")
        val red = EntityId("red-control-subject")
        val blue = EntityId("blue-control-subject")
        val state = coloredCreature(
            coloredCreature(card(colorWordSource(game(), words, near), source, active, Zone.BATTLEFIELD),
                red, near, Color.RED),
            blue, near, Color.BLUE,
        ).updateEntity(source) {
            it.with(TimestampComponent(2L))
                .with(ContinuousEffectSourceComponent(listOf(ContinuousEffectData(
                    modification = Modification.ChangeController(active),
                    affectsFilter = AffectsFilter.Generic(GroupFilter(GameObjectFilter.Creature.withColor(Color.RED))),
                ))))
        }.addFloatingEffect(
            layer = Layer.CONTROL,
            modification = SerializableModification.ChangeController(far),
            affectedEntities = setOf(words),
            duration = Duration.EndOfTurn,
            context = EffectContext(words, near),
            timestamp = 1L,
        )
        state.projectedState.getController(red) shouldBe active
        state.projectedState.getController(blue) shouldBe near
    }

    test("projected color protection follows control changes on the word source and the protected creature") {
        val words = EntityId("protection-words")
        val protected = EntityId("protected")
        val initial = card(colorWordSource(game(), words, near), protected, active, Zone.BATTLEFIELD)
            .updateEntity(protected) { it.with(ProtectionComponent(colors = setOf(Color.RED))) }
        initial.projectedState.hasKeyword(protected, "PROTECTION_FROM_BLUE") shouldBe true
        initial.projectedState.hasKeyword(protected, "PROTECTION_FROM_RED") shouldBe false

        val sourceMoved = initial.addFloatingEffect(
            layer = Layer.CONTROL,
            modification = SerializableModification.ChangeController(far),
            affectedEntities = setOf(words),
            duration = Duration.EndOfTurn,
            context = EffectContext(words, near),
        )
        sourceMoved.projectedState.hasKeyword(protected, "PROTECTION_FROM_RED") shouldBe true
        sourceMoved.projectedState.hasKeyword(protected, "PROTECTION_FROM_BLUE") shouldBe false

        val protectedMoved = initial.addFloatingEffect(
            layer = Layer.CONTROL,
            modification = SerializableModification.ChangeController(far),
            affectedEntities = setOf(protected),
            duration = Duration.EndOfTurn,
            context = EffectContext(protected, active),
        )
        protectedMoved.projectedState.hasKeyword(protected, "PROTECTION_FROM_RED") shouldBe true
        protectedMoved.projectedState.hasKeyword(protected, "PROTECTION_FROM_BLUE") shouldBe false
        TextChanges.of(protectedMoved, protected) shouldBe null
    }
})
