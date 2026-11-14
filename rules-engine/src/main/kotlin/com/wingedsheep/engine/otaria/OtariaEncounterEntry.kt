package com.wingedsheep.engine.otaria

import com.wingedsheep.engine.core.CardEntityFactory
import com.wingedsheep.engine.core.ExecutionResult
import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.core.OtariaEncounterStartedEvent
import com.wingedsheep.engine.core.OtariaEncounterSupportHandCreatedEvent
import com.wingedsheep.engine.core.OtariaEncounterEntryChoiceContinuation
import com.wingedsheep.engine.core.OtariaEncounterEntryCopyContinuation
import com.wingedsheep.engine.core.OtariaReinforcementRolledEvent
import com.wingedsheep.engine.core.Outcome
import com.wingedsheep.engine.core.FinishOtariaEncounterEntryContinuation
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.effects.EffectEntryChoices
import com.wingedsheep.engine.handlers.effects.FaceDownTurnUp
import com.wingedsheep.engine.handlers.effects.NewBattlefieldObject
import com.wingedsheep.engine.handlers.effects.ZoneEntryOptions
import com.wingedsheep.engine.handlers.effects.ZoneTransitionService
import com.wingedsheep.engine.handlers.effects.copy.EffectCopyEntry
import com.wingedsheep.engine.handlers.effects.token.TokenArt
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.identity.FaceDownComponent
import com.wingedsheep.engine.state.components.identity.FaceDownModeComponent
import com.wingedsheep.engine.state.components.identity.MorphDataComponent
import com.wingedsheep.engine.state.components.identity.OwnerComponent
import com.wingedsheep.engine.state.components.identity.PlayerComponent
import com.wingedsheep.engine.state.components.identity.TokenComponent
import com.wingedsheep.engine.state.components.identity.copiableCardComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.CreatureStats
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.EntersAsCopy
import com.wingedsheep.sdk.scripting.EntersOnlyIfPaid
import com.wingedsheep.sdk.scripting.EntersWithChoice
import com.wingedsheep.sdk.scripting.ChoiceType
import com.wingedsheep.sdk.scripting.GrantKeyword
import com.wingedsheep.sdk.scripting.KeywordAbility
import com.wingedsheep.sdk.scripting.effects.FaceDownMode
import com.wingedsheep.sdk.scripting.references.Player
import kotlinx.serialization.Serializable

@Serializable
internal data class OtariaEncounterEntryPlan(
    val actorId: EntityId,
    val primaryId: EntityId,
    val entrants: List<EntityId>,
    val morphData: MorphDataComponent?,
)

/**
 * Materializes a drawn creature and its rule-supplied support as one battlefield entry.
 * Entry decisions finish before the exploration flow determines initiative.
 */
internal class OtariaEncounterEntry(private val zones: ZoneTransitionService) {
    private val cards: CardRegistry = zones.cardRegistry

    fun start(state: GameState, finishExploration: Boolean = false): ExecutionResult {
        val mode = state.otaria ?: return ExecutionResult.error(state, "This is not an Otaria game")
        val pending = mode.pendingExploration
            ?: return ExecutionResult.error(state, "No Otaria exploration is pending")
        if (state.gameOver || state.pendingDecision != null || state.activePlayerId != pending.explorerId ||
            pending.explorerId !in state.activePlayers || pending.stage != OtariaExplorationStage.DRAWN ||
            pending.card.kind !in setOf(OtariaCardKind.CREATURE, OtariaCardKind.BOSS)
        ) return ExecutionResult.error(state, "The pending exploration is not an active creature encounter")
        val explorer = mode.players[pending.explorerId]
            ?: return ExecutionResult.error(state, "The explorer is not on the Otaria board")
        val seats = state.activePlayers
        val explorerIndex = seats.indexOf(pending.explorerId)
        if (seats.size < 2 || seats[(explorerIndex + 1) % seats.size] != pending.managerId ||
            pending.managerId !in mode.players
        ) return ExecutionResult.error(state, "The encounter manager is not the player to the explorer's left")
        if (explorer.position !in OtariaBoard.tiles) {
            return ExecutionResult.error(state, "The explorer is not on the Otaria map")
        }
        val primary = cards.getCard(pending.card.name)
            ?: return ExecutionResult.error(state, "Encounter card not found in registry: ${pending.card.name}")
        if (!primary.typeLine.isCreature) {
            return ExecutionResult.error(state, "The encountered card is not a creature: ${pending.card.name}")
        }
        val isMorph = primary.keywordAbilities.any { it is KeywordAbility.Morph }
        val support = mode.encounterSupport.forCard(pending.source, pending.card.name) ?: OtariaEncounterSupport()
        val definitions = mutableMapOf<String, CardDefinition>()
        for (group in support.reinforcements) {
            val card = group.content as? OtariaReinforcementContent.Card ?: continue
            val definition = cards.getCard(card.name)
                ?: return ExecutionResult.error(state, "Encounter support card not found in registry: ${card.name}")
            definitions[card.name] = definition
        }

        val cappedNames = support.reinforcements
            .mapNotNull { group ->
                (group.content as? OtariaReinforcementContent.Card)?.name
                    ?.takeIf { group.maximumCopiesOnBattlefield != null }
            }
            .distinct()
        val projected = state.projectedState
        val counts = cappedNames.associateWith { name ->
            state.getBattlefield().count { id ->
                !projected.isFaceDown(id) &&
                    (projected.getName(id) ?: state.getEntity(id)?.get<CardComponent>()?.name) == name
            } + if (primary.name == name && !isMorph) 1 else 0
        }
        val (plan, nextRng) = OtariaReinforcementPlanner.plan(support, state.rng, counts)

        val (actorId, afterActorId) = state.newEntity()
        val (primaryId, afterPrimaryId) = afterActorId.newEntity()
        val primaryContainer = CardEntityFactory.create(primary, actorId)
        val morphData = if (isMorph) {
            val cardId = requireNotNull(primaryContainer.get<CardComponent>()).cardDefinitionId
            FaceDownTurnUp.dataFor(primary, cardId, FaceDownMode.MORPH)
                ?: return ExecutionResult.error(state, "The encounter's morph procedure is missing")
        } else null
        val encounter = OtariaEncounter(
            explorerId = pending.explorerId,
            managerId = pending.managerId,
            position = explorer.position,
            source = pending.source,
            primaryId = primaryId,
            pileCard = pending.card,
        )
        var current = afterPrimaryId.copy(
            rng = nextRng,
            otaria = mode.copy(encounters = mode.encounters + (actorId to encounter)),
        ).withEntity(actorId, ComponentContainer.of(PlayerComponent("Encounter"), ManaPoolComponent()))
            .withEntity(primaryId, primaryContainer)
        val entrants = mutableListOf(primaryId)
        val supportHand = mutableListOf<EntityId>()

        for (group in plan.groups) {
            repeat(group.count) {
                val (id, allocated) = current.newEntity()
                current = allocated
                val container = when (val item = group.content) {
                    is OtariaReinforcementContent.Card ->
                        CardEntityFactory.create(definitions.getValue(item.name), actorId)
                    is OtariaReinforcementContent.CreatureToken -> creatureToken(item, actorId)
                    OtariaReinforcementContent.EncounteredCardCopyToken ->
                        copyToken(primaryContainer, actorId, isMorph)
                }
                current = current.withEntity(id, container)
                if (group.content is OtariaReinforcementContent.Card &&
                    group.content.destination == OtariaReinforcementDestination.HAND
                ) {
                    current = current.addToZone(ZoneKey(actorId, Zone.HAND), id)
                    supportHand.add(id)
                } else {
                    entrants.add(id)
                }
            }
        }

        val entryPlan = OtariaEncounterEntryPlan(actorId, primaryId, entrants.toList(), morphData)
        if (finishExploration) current = current.pushContinuation(FinishOtariaEncounterEntryContinuation(actorId))
        val result = continueEntry(current, entryPlan, EffectContext(sourceId = primaryId, controllerId = actorId))
        if (result.outcome is Outcome.Rejected) return ExecutionResult.rejected(state, result.outcome.reason)
        val events = plan.rolls.map { OtariaReinforcementRolledEvent(actorId, it) } +
            if (supportHand.isEmpty()) emptyList() else listOf(OtariaEncounterSupportHandCreatedEvent(actorId, supportHand))
        return result.copy(events = events + result.events)
    }

    fun continueEntry(state: GameState, plan: OtariaEncounterEntryPlan, context: EffectContext): ExecutionResult {
        val mode = state.otaria ?: return ExecutionResult.error(state, "This is not an Otaria game")
        val pending = mode.pendingExploration ?: return ExecutionResult.error(state, "No Otaria exploration is pending")
        val encounter = mode.encounters[plan.actorId]
            ?: return ExecutionResult.error(state, "The Otaria encounter is no longer active")
        if (pending.explorerId != encounter.explorerId || pending.managerId != encounter.managerId ||
            state.activePlayerId != encounter.explorerId || state.pendingDecision != null ||
            encounter.primaryId != plan.primaryId || context.controllerId != plan.actorId ||
            plan.entrants.firstOrNull() != plan.primaryId || plan.entrants.distinct().size != plan.entrants.size
        ) return ExecutionResult.error(state, "The pending Otaria encounter entry is invalid")
        if (plan.entrants.any {
                state.getEntity(it)?.get<CardComponent>() == null || state.objectRef(it) != null ||
                    state.logicalZone(it) != null
            }
        ) return ExecutionResult.error(state, "An Otaria encounter entrant is no longer available")

        val entrants = plan.entrants.map { id ->
            val isMorph = id == plan.primaryId && plan.morphData != null
            NewBattlefieldObject(
                id,
                ZoneEntryOptions(
                    faceDown = isMorph,
                    faceDownMode = if (isMorph) FaceDownMode.MORPH else null,
                    morphData = if (isMorph) plan.morphData else null,
                ),
            )
        }
        val unsupported = unsupportedEntry(state, entrants, context)
        if (unsupported != null) return ExecutionResult.error(state, unsupported)
        val faceUpEntrants = entrants.filterNot { it.options.faceDown }.associate { it.entityId to plan.actorId }
        EffectCopyEntry.prepare(state, context, faceUpEntrants, cards, zones.predicateEvaluator) { asking, id, replacement ->
            OtariaEncounterEntryCopyContinuation(plan, asking, id, replacement)
        }?.let { return it.toExecutionResult() }
        EffectEntryChoices.prepare(state, context, faceUpEntrants, cards, zones.predicateEvaluator) { asking, id, question ->
            OtariaEncounterEntryChoiceContinuation(plan, asking, id, question)
        }?.let { return it.toExecutionResult() }

        val prepared = entrants.map { entrant ->
            val answers = context.entryChoices[entrant.entityId]
            entrant.copy(options = entrant.options.copy(
                entryCopy = context.entryCopies[entrant.entityId],
                entryChoices = answers?.values.orEmpty(),
                grantedRiotModes = answers?.riotModes.orEmpty(),
            ))
        }
        val entered = zones.enterNewObjects(state, prepared)
        var current = entered.state
        if (plan.primaryId !in current.getBattlefield() || !current.projectedState.isCreature(plan.primaryId)) {
            return ExecutionResult.error(state, "The encountered creature could not enter the battlefield")
        }
        for (entrant in entrants) {
            if (current.objectRef(entrant.entityId) == null) current = current.withoutEntity(entrant.entityId)
        }
        val members = entrants.mapNotNull { entrant ->
            current.objectRef(entrant.entityId)?.takeIf { entrant.entityId in current.getBattlefield() }
        }.toSet()
        val primaryRef = requireNotNull(current.objectRef(plan.primaryId))
        val assignments = members
            .filter { it != primaryRef && !current.projectedState.isCreature(it.entityId) }
            .map { OtariaManaSourceAssignment(it, primaryRef) }
        val updatedEncounter = encounter.copy(members = members, manaSources = assignments)
        val stagedMode = requireNotNull(current.otaria)
        current = current.copy(otaria = stagedMode.copy(
            encounters = stagedMode.encounters + (plan.actorId to updatedEncounter),
            pendingExploration = null,
        ))
        val events = mutableListOf<GameEvent>()
        events.addAll(entered.events)
        events.add(OtariaEncounterStartedEvent(plan.actorId, pending.explorerId, pending.managerId, primaryRef, members))
        val choice = OtariaEncounterMana.chooseNextAtEntry(current, plan.actorId)
        return choice?.copy(events = events + choice.events) ?: ExecutionResult.success(current, events)
    }

    private fun creatureToken(item: OtariaReinforcementContent.CreatureToken, actorId: EntityId): ComponentContainer {
        val types = item.subtypes.map { it.value }
        val name = "${types.joinToString(" ")} Token"
        val card = CardComponent(
            cardDefinitionId = "token:${types.joinToString("-")}",
            name = name,
            manaCost = ManaCost.ZERO,
            typeLine = TypeLine.creature(item.subtypes),
            baseStats = CreatureStats(item.power, item.toughness),
            colors = item.colors,
            ownerId = actorId,
            imageUri = TokenArt.forCreatureTypes(types),
        )
        return ComponentContainer.of(card, OwnerComponent(actorId), ControllerComponent(actorId), TokenComponent)
    }

    private fun copyToken(primary: ComponentContainer, actorId: EntityId, faceDown: Boolean): ComponentContainer {
        if (!faceDown) {
            val card = requireNotNull(primary.get<CardComponent>())
            return primary.with(card.copy(ownerId = actorId, isDoubleFaced = false, originalSetCode = null))
                .with(TokenComponent)
        }
        val public = primary.with(FaceDownComponent).with(FaceDownModeComponent(FaceDownMode.MORPH))
            .copiableCardComponent()
        return ComponentContainer.of(
            requireNotNull(public).copy(ownerId = actorId),
            OwnerComponent(actorId),
            ControllerComponent(actorId),
            TokenComponent,
        )
    }

    private fun unsupportedEntry(
        state: GameState,
        entrants: List<NewBattlefieldObject>,
        context: EffectContext,
    ): String? {
        if (state.grantedReplacementEffects.any { it.replacement is EntersAsCopy }) {
            return "Otaria encounter entry cannot yet order granted copy replacements"
        }
        for (entrant in entrants) {
            if (entrant.options.faceDown) continue
            val copied = context.entryCopies[entrant.entityId]?.takeIf { it.copiedCard != null }
            val card = (copied?.let { EffectCopyEntry.apply(state, entrant.entityId, it) } ?: state)
                .getEntity(entrant.entityId)?.get<CardComponent>() ?: continue
            val replacements = cards.getCard(card.cardDefinitionId)?.script?.replacementEffects.orEmpty()
            if (replacements.any { it is EntersOnlyIfPaid || (it is EntersAsCopy && it.copyOf != null) }) {
                return "Otaria encounter entry choices are not implemented for ${card.name}"
            }
            if (replacements.filterIsInstance<EntersWithChoice>().any {
                    it.chooser != Player.You || it.choiceType == ChoiceType.OPPONENT ||
                        it.lookAtOpponentHand || it.then != null
                }
            ) return "Otaria encounter entry choices are not implemented for ${card.name}"
        }
        if (entrants.any { it.options.faceDown } && state.getBattlefield().any { id ->
                val permanent = state.getEntity(id)
                if (permanent?.has<FaceDownComponent>() != false) return@any false
                val card = permanent.get<CardComponent>() ?: return@any false
                cards.getCard(card.cardDefinitionId)?.staticAbilities.orEmpty().any {
                    it is GrantKeyword && it.keyword == Keyword.RIOT.name
                }
            }
        ) return "Otaria encounter entry cannot yet choose granted riot"
        return null
    }
}
