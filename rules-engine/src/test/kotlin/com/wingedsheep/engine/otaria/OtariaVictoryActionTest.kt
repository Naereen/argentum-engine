package com.wingedsheep.engine.otaria

import com.wingedsheep.engine.core.ActionProcessor
import com.wingedsheep.engine.core.CardsRevealedEvent
import com.wingedsheep.engine.core.ChooseColorDecision
import com.wingedsheep.engine.core.ClaimOtariaMirariVictory
import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.EngineServices
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.GameEndReason
import com.wingedsheep.engine.core.GameEndedEvent
import com.wingedsheep.engine.core.PayLifeOrEnterTappedSpellContinuation
import com.wingedsheep.engine.core.UndoCheckpointAction
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.engine.core.suspendForDecision
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.legalactions.LegalActionEnumerator
import com.wingedsheep.engine.legalactions.MeaningfulActionFilter
import com.wingedsheep.engine.mechanics.layers.Layer
import com.wingedsheep.engine.mechanics.layers.SerializableModification
import com.wingedsheep.engine.mechanics.layers.addFloatingEffect
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.GrantsCantLoseGameComponent
import com.wingedsheep.engine.state.components.battlefield.GrantsOpponentsCantWinGameComponent
import com.wingedsheep.engine.state.components.combat.AttackersDeclaredThisCombatComponent
import com.wingedsheep.engine.state.components.combat.AttackingComponent
import com.wingedsheep.engine.state.components.combat.BlockersDeclaredThisCombatComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.identity.FaceDownComponent
import com.wingedsheep.engine.state.components.identity.OwnerComponent
import com.wingedsheep.engine.state.components.identity.PlayerComponent
import com.wingedsheep.engine.state.components.identity.TokenComponent
import com.wingedsheep.engine.state.components.player.LossReason
import com.wingedsheep.engine.state.components.player.MulliganStateComponent
import com.wingedsheep.engine.state.components.player.PlayerLostComponent
import com.wingedsheep.engine.state.components.stack.SpellGrantedKeywordsComponent
import com.wingedsheep.engine.state.components.stack.SpellOnStackComponent
import com.wingedsheep.engine.view.ClientEvent
import com.wingedsheep.engine.view.ClientEventTransformer
import com.wingedsheep.engine.view.LegalActionEnricher
import com.wingedsheep.engine.view.LegalActionInfo
import com.wingedsheep.engine.view.asPriorityAction
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.Duration
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class OtariaVictoryActionTest : FunSpec({
    val p1 = EntityId("player-1")
    val p2 = EntityId("player-2")
    val p3 = EntityId("player-3")
    val players = listOf(p1, p2, p3)
    val mirari = EntityId("mirari")
    val spell = EntityId("spell")
    val registry = CardRegistry()
    val services = EngineServices(registry)
    val processor = ActionProcessor(services)
    val enumerator = LegalActionEnumerator(
        registry, services.manaSolver, services.costCalculator,
        services.predicateEvaluator, services.conditionEvaluator, services.turnManager,
    )
    val enricher = LegalActionEnricher(services.manaSolver, registry)
    val json = Json { serializersModule = engineSerializersModule; allowStructuredMapKeys = true }

    fun card(name: String, owner: EntityId) = CardComponent(
        cardDefinitionId = name,
        name = name,
        manaCost = ManaCost(emptyList()),
        typeLine = TypeLine(cardTypes = setOf(CardType.ARTIFACT)),
        ownerId = owner,
    )

    fun eligible(claimant: EntityId = p1): GameState {
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
        return GameState(
            entities = players.associateWith { ComponentContainer.of(PlayerComponent(it.value)) },
            turnOrder = players,
            activePlayerId = p1,
            priorityPlayerId = claimant,
            step = Step.UPKEEP,
            otaria = mode,
        ).withEntity(mirari, ComponentContainer.of(card("Mirari", claimant), OwnerComponent(claimant)))
    }

    fun offers(state: GameState, playerId: EntityId): List<LegalAction> =
        enumerator.enumerate(state, playerId).filter { it.action is ClaimOtariaMirariVictory }

    fun withSplitSecondSpell(state: GameState, controller: EntityId): GameState =
        state.withEntity(spell, ComponentContainer.of(
            card("A spell", controller), ControllerComponent(controller),
            SpellOnStackComponent(controller), SpellGrantedKeywordsComponent(setOf(Keyword.SPLIT_SECOND.name)),
        )).copy(stack = listOf(spell))

    test("the opponent with priority can claim an immediate victory through split second and win restrictions") {
        val base = withSplitSecondSpell(eligible(p2), p1)
        val lock = EntityId("lock")
        val state = base.withEntity(lock, ComponentContainer.of(
            card("Lock", p1), OwnerComponent(p1), ControllerComponent(p1),
            GrantsCantLoseGameComponent(), GrantsOpponentsCantWinGameComponent(),
        )).addToZone(ZoneKey(p1, Zone.BATTLEFIELD), lock)
        val offered = offers(state, p2).single()
        offered.action shouldBe ClaimOtariaMirariVictory(p2, mirari)
        processor.validate(state, offered.action) shouldBe null

        val processed = processor.process(state, offered.action)
        val result = processed.result
        result.error shouldBe null
        result.state.gameOver shouldBe true
        result.state.winnerId shouldBe p2
        result.state.otaria?.mirariVictoryClaimed shouldBe true
        result.state.stack shouldBe listOf(spell)
        result.events.size shouldBe 2
        (result.events[0] as CardsRevealedEvent).cardIds shouldBe listOf(mirari)
        result.events[1] shouldBe GameEndedEvent(p2, GameEndReason.ALTERNATIVE_WIN)
        processed.undoPolicy shouldBe UndoCheckpointAction.CLEAR
        state.gameOver shouldBe false
        state.otaria?.mirariVictoryClaimed shouldBe false

        for (viewer in players) {
            val events = ClientEventTransformer.transform(result.events, viewer, result.state)
            (events[0] as ClientEvent.CardsRevealed).cardNames shouldBe listOf("Mirari")
            (events[1] as ClientEvent.GameEnded).winnerId shouldBe p2
        }
    }

    test("claims and their legal-action DTOs round-trip without exposing the private reserve to another player") {
        val state = eligible(p2)
        val claim = offers(state, p2).single()
        json.decodeFromString<GameAction>(json.encodeToString<GameAction>(claim.action)) shouldBe claim.action
        val info = enricher.enrich(listOf(claim), state, p2).single()
        json.decodeFromString<LegalActionInfo>(json.encodeToString(info)) shouldBe info
        MeaningfulActionFilter.isMeaningful(info.asPriorityAction()) shouldBe true
        offers(state, p1) shouldBe emptyList()
        offers(state, p3) shouldBe emptyList()
    }

    test("every priority step offers a claim and prevents both auto-pass paths") {
        val base = eligible().updateEntity(p1) { it.with(AttackersDeclaredThisCombatComponent) }
        for (step in Step.entries.filter { it != Step.UNTAP }) {
            val state = base.copy(step = step)
            val actions = enumerator.enumerate(state, p1)
            val claim = actions.single { it.action is ClaimOtariaMirariVictory }
            processor.validate(state, claim.action) shouldBe null
            MeaningfulActionFilter.shouldAutoPass(state, p1, actions) shouldBe false
            MeaningfulActionFilter.canAutoPassWithoutEnumerating(state, p1) shouldBe false
        }
        val missing = eligible(p2).copy(priorityPlayerId = p1, step = Step.BEGIN_COMBAT)
        MeaningfulActionFilter.canAutoPassWithoutEnumerating(missing, p1) shouldBe true
    }

    test("auto-pass stops for the claim above either player's spell") {
        for (spellController in listOf(p1, p2)) {
            val state = withSplitSecondSpell(eligible(p2), spellController)
            val actions = enumerator.enumerate(state, p2)
            MeaningfulActionFilter.shouldAutoPass(state, p2, actions) shouldBe false
            val dtoActions = enricher.enrich(actions, state, p2).map { it.asPriorityAction() }
            MeaningfulActionFilter.shouldAutoPass(state, p2, dtoActions) shouldBe false
        }
    }

    test("claims are rejected outside a priority window or while a decision is pending") {
        val state = eligible()
        val action = ClaimOtariaMirariVictory(p1, mirari)
        val pending = ChooseColorDecision("decision", p2, "Choose", DecisionContext())
        val paused = state.suspendForDecision(
            question = { id -> pending.copy(id = id) },
            answer = PayLifeOrEnterTappedSpellContinuation(mirari, p2, p2, 2),
        ).state
        val invalid = listOf(
            state.copy(priorityPlayerId = p2),
            state.copy(priorityPlayerId = null),
            state.copy(step = Step.UNTAP),
            paused,
            state.updateEntity(p1) { it.with(MulliganStateComponent()) },
            state.updateEntity(p1) { it.with(PlayerLostComponent(LossReason.CONCESSION)) },
            state.copy(gameOver = true),
            state.copy(otaria = null),
        )
        for (attempt in invalid) {
            processor.validate(attempt, action) shouldNotBe null
            val result = processor.process(attempt, action).result
            result.error shouldNotBe null
            result.state shouldBe attempt
            result.events shouldBe emptyList()
            offers(attempt, p1) shouldBe emptyList()
        }
    }

    test("combat declarations happen before a claim becomes available") {
        val attackers = eligible().copy(step = Step.DECLARE_ATTACKERS)
        val attackAction = ClaimOtariaMirariVictory(p1, mirari)
        offers(attackers, p1) shouldBe emptyList()
        processor.validate(attackers, attackAction) shouldNotBe null
        val declared = attackers.updateEntity(p1) { it.with(AttackersDeclaredThisCombatComponent) }
        offers(declared, p1).single().action shouldBe attackAction
        processor.validate(declared, attackAction) shouldBe null

        val attacker = EntityId("attacker")
        val blockers = eligible(p2).copy(step = Step.DECLARE_BLOCKERS)
            .withEntity(attacker, ComponentContainer.of(
                card("Attacker", p1), ControllerComponent(p1), AttackingComponent(defenderId = p2),
            )).addToZone(ZoneKey(p1, Zone.BATTLEFIELD), attacker)
        val blockAction = ClaimOtariaMirariVictory(p2, mirari)
        offers(blockers, p2) shouldBe emptyList()
        processor.validate(blockers, blockAction) shouldNotBe null
        val blocked = blockers.updateEntity(p2) { it.with(BlockersDeclaredThisCombatComponent) }
        offers(blocked, p2).single().action shouldBe blockAction
        processor.validate(blocked, blockAction) shouldBe null
    }

    test("claims require a matching location and the actual controlled or owned card") {
        val state = eligible()
        val mode = requireNotNull(state.otaria)
        val player = mode.players.getValue(p1)
        val noLocation = state.copy(otaria = mode.copy(players = mode.players + (
            p1 to player.copy(locationCards = emptyList())
        )))
        val differentPosition = state.copy(otaria = mode.copy(players = mode.players + (
            p1 to player.copy(position = OtariaLocation.ORDER_ENCAMPMENT.position)
        )))
        val notOwned = state.updateEntity(mirari) { it.with(OwnerComponent(p2)) }
        val token = state.updateEntity(mirari) { it.with(TokenComponent) }
        val missing = state.withoutEntity(mirari)
        for (invalid in listOf(noLocation, differentPosition, notOwned, token, missing)) {
            val action = ClaimOtariaMirariVictory(p1, mirari)
            offers(invalid, p1) shouldBe emptyList()
            processor.validate(invalid, action) shouldNotBe null
        }

        val battlefield = state.copy(otaria = mode.copy(players = mode.players + (
            p1 to player.copy(reserve = emptyList())
        ))).updateEntity(mirari) { it.with(ControllerComponent(p2)) }
            .addToZone(ZoneKey(p1, Zone.BATTLEFIELD), mirari)
        offers(battlefield, p1) shouldBe emptyList()
        val stolen = battlefield.addFloatingEffect(
            layer = Layer.CONTROL,
            modification = SerializableModification.ChangeController(p1),
            affectedEntities = setOf(mirari),
            duration = Duration.EndOfTurn,
            context = EffectContext(mirari, p1),
        )
        offers(stolen, p1).single().action shouldBe ClaimOtariaMirariVictory(p1, mirari)
        processor.validate(stolen, ClaimOtariaMirariVictory(p1, mirari)) shouldBe null
        val faceDown = stolen.updateEntity(mirari) { it.with(FaceDownComponent) }
        offers(faceDown, p1) shouldBe emptyList()
        processor.validate(faceDown, ClaimOtariaMirariVictory(p1, mirari)) shouldNotBe null
    }
})
