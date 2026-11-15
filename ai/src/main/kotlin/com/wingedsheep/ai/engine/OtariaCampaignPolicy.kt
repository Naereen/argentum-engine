package com.wingedsheep.ai.engine

import com.wingedsheep.ai.engine.knowledge.IntentCatalog
import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.otaria.*
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.battlefield.TappedComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.LifeTotalComponent
import com.wingedsheep.engine.state.components.identity.OwnerComponent
import com.wingedsheep.engine.state.components.identity.TokenComponent
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId

/**
 * Chooses campaign actions and their map/reserve parameters, never Magic plays or combat declarations.
 * Only typed campaign continuations are intercepted; everything else stays with the engine AI.
 * Reads public board facts and the deciding explorer's cards, never opponents' reserves or pile order.
 */
internal class OtariaCampaignPolicy(private val intents: IntentCatalog = IntentCatalog.NONE) {
    fun respond(state: GameState, decision: PendingDecision): DecisionResponse? {
        val mode = state.otaria ?: return null
        val seat = decision.playerId
        val player = mode.players[seat] ?: return null
        val suspension = (state.peekContinuation() as? Suspension)
            ?.takeIf { it.question.id == decision.id } ?: return null
        return when (val choice = suspension.answer) {
            is OtariaTurnChoiceContinuation -> {
                if (choice.playerId != seat || decision !is ChooseOptionDecision) return null
                val action = turnAction(state, seat, player)
                check(action.ordinal in decision.options.indices) { "Missing Otaria turn action: $action" }
                OptionChosenResponse(decision.id, action.ordinal)
            }
            is OtariaMovementChoiceContinuation -> {
                if (choice.playerId != seat || decision !is ChooseOptionDecision) return null
                check(choice.destinations.size == decision.options.size && choice.destinations.isNotEmpty())
                val fleeing = strongerThreats(state, seat, player.position).isNotEmpty()
                val target = mirariDestination(state, seat, player)
                val distances = target?.let { distancesToStars.getValue(it) }
                val index = when {
                    distances != null -> {
                        choice.destinations.indices.minWith(
                            compareBy<Int> { distances.getValue(choice.destinations[it]) }
                                .thenBy {
                                    if (fleeing) -escapeValue(state, seat, choice.destinations[it], player.position)
                                    else danger(state, seat, choice.destinations[it])
                                },
                        )
                    }
                    fleeing -> choice.destinations.indices.maxBy {
                        escapeValue(state, seat, choice.destinations[it], player.position)
                    }
                    else -> choice.destinations.indices.maxBy {
                        navigationValue(state, seat, player, choice.destinations[it])
                    }
                }
                OptionChosenResponse(decision.id, index)
            }
            is OtariaExplorationChoiceContinuation -> {
                if (choice.playerId != seat || decision !is ChooseOptionDecision) return null
                check(choice.choices.size == decision.options.size && choice.choices.isNotEmpty())
                val ready = readyForStar(state, seat) && locationValue(state, player, player.position) > 0 &&
                    danger(state, seat, player.position) == 0.0
                val index = choice.choices.indices.maxBy {
                    val option = choice.choices[it]
                    (if (option.exploreLocation == ready) 10 else 0) + (if (option.takeLand) 1 else 0)
                }
                OptionChosenResponse(decision.id, index)
            }
            is OtariaPreparationChoiceContinuation -> {
                if (choice.playerId != seat || decision !is SelectCardsDecision) return null
                val ranked = reserveRanking(state, seat, decision.options)
                val librarySize = state.getZone(seat, Zone.LIBRARY).size
                val emergency = librarySize <= DECK_OUT_LIMIT
                val nonMirari = ranked.filter { it.second.isFinite() }
                val candidates = if (emergency && nonMirari.isEmpty()) ranked else nonMirari
                val threshold = preparationThreshold(state, seat)
                val desired = if (emergency) (LIBRARY_BUFFER - librarySize).coerceAtLeast(1)
                    else maxOf(candidates.count { it.second > threshold },
                        if (!readyForStar(state, seat) && candidates.isNotEmpty()) 1 else 0)
                val count = maxOf(decision.minSelections, desired)
                    .coerceAtMost(decision.maxSelections)
                check(candidates.size >= decision.minSelections) { "Not enough selectable reserve cards" }
                CardsSelectedResponse(decision.id, candidates.take(count).map { it.first })
            }
            is OtariaDiscoveryChoiceContinuation -> {
                if (choice.playerId != seat || decision !is YesNoDecision) return null
                YesNoResponse(decision.id, decision.yesAvailable)
            }
            else -> null
        }
    }

    private fun turnAction(state: GameState, seat: EntityId, player: OtariaPlayerState): OtariaTurnAction {
        if (state.getZone(seat, Zone.LIBRARY).size <= DECK_OUT_LIMIT && player.reserve.isNotEmpty()) {
            return OtariaTurnAction.PREPARE
        }
        val ownStrength = strength(state, seat)
        if (strongerThreats(state, seat, player.position).isNotEmpty()) return OtariaTurnAction.MOVE
        if (state.getZone(seat, Zone.LIBRARY).size > DECK_OUT_LIMIT &&
            hasFavorableCombat(state, seat, player.position, ownStrength)
        ) return OtariaTurnAction.COMBAT

        val target = mirariDestination(state, seat, player)
        if (target != null && target != player.position) return OtariaTurnAction.MOVE

        val readyForStar = readyForStar(state, seat)
        val bestReserve = reserveRanking(state, seat, player.reserve).firstOrNull()
        if (bestReserve != null && (bestReserve.second > preparationThreshold(state, seat) || !readyForStar)) {
            return OtariaTurnAction.PREPARE
        }
        if (!readyForStar && state.getZone(seat, Zone.LIBRARY).size > DECK_OUT_LIMIT &&
            state.getZone(seat, Zone.HAND).size <= DEVELOPMENT_HAND_SIZE
        ) return OtariaTurnAction.PREPARE
        if (readyForStar && locationValue(state, player, player.position) > 0 &&
            danger(state, seat, player.position) == 0.0
        ) {
            return OtariaTurnAction.EXPLORE
        }
        if (state.getZone(seat, Zone.LIBRARY).size > DECK_OUT_LIMIT && readyForStar &&
            favorableOpponent(state, seat, player.position, ownStrength) != null
        ) {
            return OtariaTurnAction.MOVE
        }
        if (readyForStar && OtariaLocation.entries.any { locationValue(state, player, it.position) > 0 }) {
            return OtariaTurnAction.MOVE
        }
        return OtariaTurnAction.EXPLORE
    }

    private data class CampaignThreat(
        val position: OtariaPosition,
        val strength: Double,
    )

    private fun strongerThreats(state: GameState, seat: EntityId, position: OtariaPosition): List<CampaignThreat> {
        val ownStrength = strength(state, seat)
        val mode = requireNotNull(state.otaria)
        val players = mode.players.entries.mapNotNull { (other, player) ->
            if (other == seat || other !in state.activePlayers) return@mapNotNull null
            val opponentStrength = strength(state, other)
            if (opponentStrength <= ownStrength * THREAT_STRENGTH_RATIO ||
                OtariaBoard.distance(position, player.position) > movementRange(state, other)
            ) return@mapNotNull null
            CampaignThreat(player.position, opponentStrength)
        }
        val encounters = mode.encounters.entries.mapNotNull { (actor, encounter) ->
            if (seat !in encounter.participants) return@mapNotNull null
            val encounterStrength = strength(state, actor)
            if (encounterStrength <= ownStrength * THREAT_STRENGTH_RATIO ||
                OtariaBoard.distance(position, encounter.position) > 1
            ) return@mapNotNull null
            CampaignThreat(encounter.position, encounterStrength)
        }
        return players + encounters
    }

    private fun hasFavorableCombat(
        state: GameState,
        seat: EntityId,
        position: OtariaPosition,
        ownStrength: Double,
    ): Boolean {
        val mode = requireNotNull(state.otaria)
        val favorablePlayer = mode.players.any { (other, opponent) ->
            other != seat && other in state.activePlayers &&
                OtariaBoard.distance(position, opponent.position) <= 1 &&
                ownStrength > strength(state, other) * COMBAT_STRENGTH_RATIO
        }
        val favorableEncounter = mode.encounters.any { (actor, encounter) ->
            seat in encounter.participants && encounter.members.any {
                state.objectRef(it.entityId) == it && it.entityId in state.getBattlefield()
            } && OtariaBoard.distance(position, encounter.position) <= 1 &&
                ownStrength > strength(state, actor) * COMBAT_STRENGTH_RATIO
        }
        return ownStrength > 0 && (favorablePlayer || favorableEncounter)
    }

    private fun favorableOpponent(
        state: GameState,
        seat: EntityId,
        position: OtariaPosition,
        ownStrength: Double,
    ): Pair<EntityId, OtariaPosition>? {
        if (ownStrength <= 0) return null
        return requireNotNull(state.otaria).players.entries.asSequence()
            .filter { (other, _) -> other != seat && other in state.activePlayers }
            .map { (other, player) -> Triple(other, player.position, strength(state, other)) }
            .filter { (_, _, opponentStrength) -> ownStrength > opponentStrength * COMBAT_STRENGTH_RATIO }
            .minWithOrNull(
                compareBy<Triple<EntityId, OtariaPosition, Double>> {
                    (OtariaBoard.distance(position, it.second) - 1).coerceAtLeast(0)
                }.thenBy { it.third },
            )
            ?.let { it.first to it.second }
    }

    private fun escapeValue(
        state: GameState,
        seat: EntityId,
        destination: OtariaPosition,
        currentPosition: OtariaPosition,
    ): Double {
        val threats = strongerThreats(state, seat, currentPosition)
        val distanceFromThreats = threats.sumOf { threat ->
            OtariaBoard.distance(destination, threat.position).coerceAtMost(MAX_ESCAPE_DISTANCE) *
                (1.0 + threat.strength / ESCAPE_STRENGTH_SCALE)
        }
        return distanceFromThreats - danger(state, seat, destination)
    }

    private fun readyForStar(state: GameState, seat: EntityId): Boolean {
        if (state.getZone(seat, Zone.LIBRARY).size <= DECK_OUT_LIMIT ||
            (state.getEntity(seat)?.get<LifeTotalComponent>()?.life ?: 0) < MIN_STAR_LIFE
        ) return false
        val projected = state.projectedState
        val creatures = projected.getBattlefieldControlledBy(seat).filter { projected.isCreature(it) }
        val blockers = creatures.filter {
            state.step != Step.OTARIA_EXPLORATION || state.getEntity(it)?.has<TappedComponent>() != true
        }
        val attackers = creatures.filter {
            !projected.hasKeyword(it, Keyword.DEFENDER) && (projected.getPower(it) ?: 0) > 0
        }
        val aerialBlockers = blockers.filter {
            projected.hasKeyword(it, Keyword.FLYING) || projected.hasKeyword(it, Keyword.REACH)
        }
        return blockers.size >= 3 && attackers.size >= 2 &&
            creatures.sumOf { (projected.getPower(it) ?: 0).coerceAtLeast(0) } >= MIN_STAR_POWER &&
            blockers.sumOf { (projected.getToughness(it) ?: 0).coerceAtLeast(0) } >= MIN_STAR_TOUGHNESS &&
            aerialBlockers.sumOf { (projected.getToughness(it) ?: 0).coerceAtLeast(0) } >= MIN_AERIAL_TOUGHNESS
    }

    private fun mirariDestination(state: GameState, seat: EntityId, player: OtariaPlayerState): OtariaPosition? {
        if (!hasMirari(state, seat, player) || player.locationCards.isEmpty()) return null
        return player.locationCards.distinct().minWith(
            compareBy<OtariaLocation> { distancesToStars.getValue(it.position).getValue(player.position) }
                .thenBy { danger(state, seat, it.position) },
        ).position
    }

    private fun hasMirari(state: GameState, seat: EntityId, player: OtariaPlayerState): Boolean {
        if (player.reserve.any { id ->
                val entity = state.getEntity(id)
                entity?.get<CardComponent>()?.name == OtariaSetup.MIRARI &&
                    entity.get<OwnerComponent>()?.playerId == seat && !entity.has<TokenComponent>()
            }
        ) return true
        val projected = state.projectedState
        return projected.getBattlefieldControlledBy(seat).any { id ->
            val entity = requireNotNull(state.getEntity(id))
            val card = entity.get<CardComponent>()
            !projected.isFaceDown(id) && !entity.has<TokenComponent>() &&
                card != null && (projected.getName(id) ?: card.name) == OtariaSetup.MIRARI
        }
    }

    private fun locationValue(state: GameState, player: OtariaPlayerState, position: OtariaPosition): Double {
        val mode = requireNotNull(state.otaria)
        val location = OtariaBoard.tiles.getValue(position).location ?: return 0.0
        if (!player.hasExploredLocationAwayFromStart && location == player.startingLocation) return 0.0
        if (mode.starPile.cards.isEmpty() && mode.starPile.discard.isEmpty()) return 0.0
        val remaining = location.explorationLimit(mode.players.size) - mode.explorationsUsed.getValue(location)
        if (remaining <= 0) return 0.0
        return 12.0 + remaining.toDouble() / location.explorationLimit(mode.players.size) +
            if (location !in player.locationCards) 2.0 else 0.0
    }

    private fun navigationValue(
        state: GameState,
        seat: EntityId,
        player: OtariaPlayerState,
        position: OtariaPosition,
    ): Double {
        val range = movementRange(state, seat)
        val goal = if (!readyForStar(state, seat)) 0.0 else OtariaLocation.entries.maxOf { location ->
            val value = locationValue(state, player, location.position)
            val distance = distancesToStars.getValue(location.position).getValue(position)
            if (value == 0.0) 0.0 else value / (1 + (distance + range - 1) / range)
        }
        val combatGoal = if (state.getZone(seat, Zone.LIBRARY).size > DECK_OUT_LIMIT &&
            readyForStar(state, seat)
        ) {
            favorableOpponent(state, seat, position, strength(state, seat))?.let { (_, target) ->
                val remaining = (OtariaBoard.distance(position, target) - 1).coerceAtLeast(0)
                COMBAT_GOAL_VALUE / (1 + (remaining + range - 1) / range)
            } ?: 0.0
        } else 0.0
        return goal + combatGoal + terrainValue(state, seat, position) - danger(state, seat, position)
    }

    private fun terrainValue(state: GameState, seat: EntityId, position: OtariaPosition): Double {
        val tile = OtariaBoard.tiles.getValue(position)
        val pile = requireNotNull(state.otaria).colorPiles.getValue(tile.color)
        if (pile.cards.isEmpty() && pile.discard.isEmpty()) return 0.0
        val cards = state.getZone(seat, Zone.LIBRARY) + state.getZone(seat, Zone.HAND)
        val matching = cards.count { tile.color in state.getEntity(it)?.get<CardComponent>()?.colors.orEmpty() }
        return 0.5 + matching.toDouble() / cards.size.coerceAtLeast(1)
    }

    private fun danger(state: GameState, seat: EntityId, position: OtariaPosition): Double {
        val mode = requireNotNull(state.otaria)
        val own = strength(state, seat)
        return mode.players.entries.sumOf { (other, player) ->
            if (other == seat || other !in state.activePlayers || strength(state, other) <= own ||
                OtariaBoard.distance(position, player.position) > movementRange(state, other)
            ) 0.0 else when (OtariaBoard.distance(position, player.position)) {
                0L -> 14.0
                1L -> 10.0
                else -> 6.0
            }
        } + mode.encounters.entries.sumOf { (actor, encounter) ->
            if (encounter.position == position && strength(state, actor) > own) 14.0 else 0.0
        }
    }

    private fun strength(state: GameState, seat: EntityId): Double {
        val projected = state.projectedState
        return projected.getBattlefieldControlledBy(seat).sumOf {
            if (projected.isCreature(it)) {
                (projected.getPower(it) ?: 0).coerceAtLeast(0).toDouble() +
                    (projected.getToughness(it) ?: 0).coerceAtLeast(0)
            } else 0.0
        }
    }

    private fun movementRange(state: GameState, seat: EntityId): Int =
        if (state.projectedState.getBattlefieldControlledBy(seat).size >= 10) 1 else 2

    private fun reserveRanking(state: GameState, seat: EntityId, options: List<EntityId>): List<Pair<EntityId, Double>> {
        val value = CardSelectionValue.of(state, seat, intents, drawnLater = true)
        val deck = (state.getZone(seat, Zone.LIBRARY) + state.getZone(seat, Zone.HAND))
            .map { requireNotNull(state.getEntity(it)?.get<CardComponent>()) }
        val emergency = state.getZone(seat, Zone.LIBRARY).size <= DECK_OUT_LIMIT
        val ranked = options.map { id ->
            val card = requireNotNull(state.getEntity(id)?.get<CardComponent>()) { "Missing reserve card: $id" }
            val curve = if (!card.isLand && deck.none { !it.isLand && curveBucket(it.manaValue) == curveBucket(card.manaValue) }) 2.0 else 0.0
            val landBalance = if (card.isLand) {
                if (deck.count { it.isLand } < deck.size * 0.4) 4.0 else -4.0
            } else 0.0
            id to if (card.name == OtariaSetup.MIRARI) Double.NEGATIVE_INFINITY
                else value.score(id) + curve + landBalance
        }.sortedByDescending { it.second }
        // Even Mirari can be used as the last available deck-out buffer, but not as routine deck filler.
        return if (emergency) ranked else ranked.filter { it.second.isFinite() }
    }

    private fun preparationThreshold(state: GameState, seat: EntityId): Double =
        maxOf(5.0, CardSelectionValue.of(state, seat, intents, drawnLater = true).averageDraw() + 2.0)

    private fun curveBucket(manaValue: Int): Int = manaValue.coerceIn(2, 5)

    private companion object {
        const val DECK_OUT_LIMIT = 4
        const val LIBRARY_BUFFER = 8
        const val DEVELOPMENT_HAND_SIZE = 3
        const val MIN_STAR_LIFE = 9
        const val MIN_STAR_POWER = 12
        const val MIN_STAR_TOUGHNESS = 15
        const val MIN_AERIAL_TOUGHNESS = 6
        const val THREAT_STRENGTH_RATIO = 1.25
        const val COMBAT_STRENGTH_RATIO = 1.5
        const val COMBAT_GOAL_VALUE = 10.0
        const val MAX_ESCAPE_DISTANCE = 5L
        const val ESCAPE_STRENGTH_SCALE = 20.0

        val distancesToStars by lazy {
            OtariaLocation.entries.associate { it.position to distancesFrom(it.position) }
        }

        fun distancesFrom(start: OtariaPosition): Map<OtariaPosition, Int> {
            val distances = mutableMapOf(start to 0)
            val queue = ArrayDeque(listOf(start))
            while (queue.isNotEmpty()) {
                val position = queue.removeFirst()
                for (next in OtariaBoard.neighbours(position)) {
                    if (next !in distances) {
                        distances[next] = distances.getValue(position) + 1
                        queue.addLast(next)
                    }
                }
            }
            return distances
        }
    }
}
