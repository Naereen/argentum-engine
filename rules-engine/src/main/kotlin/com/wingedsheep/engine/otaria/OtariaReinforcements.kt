package com.wingedsheep.engine.otaria

import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.model.GameRng
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface OtariaReinforcementCount {
    @Serializable
    @SerialName("Fixed")
    data class Fixed(val value: Int) : OtariaReinforcementCount {
        init {
            require(value > 0) { "A fixed reinforcement count must be positive" }
        }
    }

    @Serializable
    @SerialName("Die")
    data class Die(
        val sides: Int,
        val modifier: Int = 0,
        val minimum: Int = 0,
    ) : OtariaReinforcementCount {
        init {
            require(sides >= 2) { "A reinforcement die must have at least two sides" }
            require(minimum >= 0) { "A reinforcement count cannot have a negative minimum" }
            require(sides.toLong() + modifier <= Int.MAX_VALUE) { "Reinforcement count can overflow" }
        }
    }
}

@Serializable
enum class OtariaReinforcementDestination {
    BATTLEFIELD,
    HAND,
}

@Serializable
sealed interface OtariaReinforcementContent {
    @Serializable
    @SerialName("Card")
    data class Card(
        val name: String,
        val destination: OtariaReinforcementDestination = OtariaReinforcementDestination.BATTLEFIELD,
    ) : OtariaReinforcementContent {
        init {
            require(name.isNotBlank() && name == name.trim()) { "A reinforcement card needs a valid name" }
        }
    }

    @Serializable
    @SerialName("CreatureToken")
    data class CreatureToken(
        val colors: Set<Color>,
        val subtypes: Set<Subtype>,
        val power: Int,
        val toughness: Int,
    ) : OtariaReinforcementContent {
        init {
            require(subtypes.isNotEmpty()) { "A reinforcement creature token needs a type" }
            require(power >= 0 && toughness >= 0) { "Token power and toughness must not be negative" }
        }
    }

    /** A token copy of the encountered card, not an additional physical card. */
    @Serializable
    @SerialName("EncounteredCardCopyToken")
    data object EncounteredCardCopyToken : OtariaReinforcementContent
}

@Serializable
data class OtariaReinforcementGroup(
    val content: OtariaReinforcementContent,
    val count: OtariaReinforcementCount,
    val maximumCopiesOnBattlefield: Int? = null,
) {
    init {
        if (maximumCopiesOnBattlefield != null) {
            require(maximumCopiesOnBattlefield > 0) { "A copy limit must be positive" }
            require(content is OtariaReinforcementContent.Card &&
                content.destination == OtariaReinforcementDestination.BATTLEFIELD) {
                "A copy limit only applies to cards entering the battlefield"
            }
        }
    }
}

@Serializable
data class OtariaEncounterSupport(
    val reinforcements: List<OtariaReinforcementGroup> = emptyList(),
    val faceUpTriggerIgnoresRange: Boolean = false,
)

@Serializable
data class OtariaEncounterSupportTable(
    val colors: Map<Color, Map<String, OtariaEncounterSupport>> = emptyMap(),
    val stars: Map<String, OtariaEncounterSupport> = emptyMap(),
) {
    fun forCard(source: OtariaExplorationSource, cardName: String): OtariaEncounterSupport? = when (source) {
        is OtariaExplorationSource.Star -> stars[cardName]
        is OtariaExplorationSource.Terrain -> colors[source.color]?.get(cardName)
    }

    internal fun snapshot(): OtariaEncounterSupportTable {
        fun copyRules(rules: Map<String, OtariaEncounterSupport>) = rules.mapValues { (name, support) ->
            require(name.isNotBlank() && name == name.trim()) { "An encounter support key needs a valid card name" }
            support.copy(reinforcements = support.reinforcements.map { group ->
                val content = when (val item = group.content) {
                    is OtariaReinforcementContent.CreatureToken ->
                        item.copy(colors = item.colors.toSet(), subtypes = item.subtypes.toSet())
                    else -> item
                }
                group.copy(content = content)
            })
        }
        return OtariaEncounterSupportTable(colors.mapValues { copyRules(it.value) }, copyRules(stars))
    }
}

@Serializable
data class OtariaReinforcementRoll(
    val sides: Int,
    val natural: Int,
    val countBeforeCopyLimit: Int,
)

@Serializable
data class OtariaResolvedReinforcement(
    val content: OtariaReinforcementContent,
    val count: Int,
)

@Serializable
data class OtariaReinforcementPlan(
    val groups: List<OtariaResolvedReinforcement>,
    val rolls: List<OtariaReinforcementRoll>,
)

/**
 * Resolves the quantities only. The caller must put cards and tokens onto the battlefield as one
 * simultaneous entry, supply cards from the unlimited reinforcement stock, and publish Otaria
 * roll events. These rule rolls are not modified or observed by Magic card abilities.
 * [copiesOnBattlefield] must include the encountered card when it will enter in the same batch.
 * A primary encounter can exceed a copy limit; in that case no extra copies are planned.
 */
object OtariaReinforcementPlanner {
    fun plan(
        support: OtariaEncounterSupport,
        rng: GameRng,
        copiesOnBattlefield: Map<String, Int>,
    ): Pair<OtariaReinforcementPlan, GameRng> {
        require(copiesOnBattlefield.values.all { it >= 0 }) { "Copy counts must not be negative" }
        var current = rng
        val rolls = mutableListOf<OtariaReinforcementRoll>()
        val alreadyPlanned = mutableMapOf<String, Int>()
        val groups = support.reinforcements.map { group ->
            val requested = when (val count = group.count) {
                is OtariaReinforcementCount.Fixed -> count.value
                is OtariaReinforcementCount.Die -> {
                    val (natural, advanced) = current.rollOtariaDie(count.sides)
                    current = advanced
                    val value = maxOf(count.minimum.toLong(), natural.toLong() + count.modifier).toInt()
                    rolls.add(OtariaReinforcementRoll(count.sides, natural, value))
                    value
                }
            }
            val card = group.content as? OtariaReinforcementContent.Card
            val resolved = if (group.maximumCopiesOnBattlefield != null) {
                val name = requireNotNull(card).name
                val existing = requireNotNull(copiesOnBattlefield[name]) {
                    "The number of $name copies already entering or on the battlefield is required"
                }
                val available = (
                    group.maximumCopiesOnBattlefield.toLong() - existing - alreadyPlanned.getOrDefault(name, 0)
                ).coerceAtLeast(0)
                minOf(requested.toLong(), available).toInt()
            } else requested
            if (card?.destination == OtariaReinforcementDestination.BATTLEFIELD) {
                alreadyPlanned[card.name] = Math.addExact(alreadyPlanned.getOrDefault(card.name, 0), resolved)
            }
            OtariaResolvedReinforcement(group.content, resolved)
        }
        return OtariaReinforcementPlan(groups, rolls.toList()) to current
    }
}
