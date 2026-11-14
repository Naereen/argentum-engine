package com.wingedsheep.engine.otaria

import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.model.GameRng
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class OtariaReinforcementPlannerTest : FunSpec({
    val goblin = OtariaReinforcementContent.CreatureToken(
        colors = setOf(Color.RED),
        subtypes = setOf(Subtype.GOBLIN),
        power = 1,
        toughness = 1,
    )
    val legion = OtariaReinforcementContent.Card("Severed Legion")

    fun die(sides: Int, modifier: Int = 0, minimum: Int = 0) =
        OtariaReinforcementGroup(goblin, OtariaReinforcementCount.Die(sides, modifier, minimum))

    test("fixed groups do not consume randomness and preserve their distinct destinations") {
        val support = OtariaEncounterSupport(listOf(
            OtariaReinforcementGroup(legion, OtariaReinforcementCount.Fixed(2)),
            OtariaReinforcementGroup(
                OtariaReinforcementContent.Card("Mountain", OtariaReinforcementDestination.HAND),
                OtariaReinforcementCount.Fixed(1),
            ),
            OtariaReinforcementGroup(
                OtariaReinforcementContent.EncounteredCardCopyToken,
                OtariaReinforcementCount.Fixed(3),
            ),
        ))
        val rng = GameRng.seeded(19)
        val (plan, next) = OtariaReinforcementPlanner.plan(support, rng, emptyMap())
        plan.groups.map { it.count } shouldBe listOf(2, 1, 3)
        plan.groups.map { it.content } shouldBe support.reinforcements.map { it.content }
        plan.rolls shouldBe emptyList()
        next shouldBe rng
        Json.decodeFromString<OtariaEncounterSupport>(Json.encodeToString(support)) shouldBe support
        Json.decodeFromString<OtariaReinforcementPlan>(Json.encodeToString(plan)) shouldBe plan
    }

    test("dice, modifiers and minimums replay deterministically and cover every face") {
        val support = OtariaEncounterSupport(listOf(die(2), die(4, -1), die(4, 1), die(4, minimum = 2)))
        val seen = List(4) { mutableSetOf<Int>() }
        repeat(128) { seed ->
            val rng = GameRng.seeded(seed.toLong())
            val planned = OtariaReinforcementPlanner.plan(support, rng, emptyMap())
            planned shouldBe OtariaReinforcementPlanner.plan(support, rng, emptyMap())
            val (plan, next) = planned
            (next != rng) shouldBe true
            plan.rolls.size shouldBe 4
            plan.rolls.forEachIndexed { index, roll -> seen[index].add(roll.natural) }
            plan.groups.map { it.count } shouldBe listOf(
                plan.rolls[0].natural,
                plan.rolls[1].natural - 1,
                plan.rolls[2].natural + 1,
                maxOf(2, plan.rolls[3].natural),
            )
            plan.rolls.map { it.countBeforeCopyLimit } shouldBe plan.groups.map { it.count }
        }
        seen[0] shouldBe setOf(1, 2)
        seen.drop(1).forEach { it shouldBe setOf(1, 2, 3, 4) }
    }

    test("one die adds copies up to the battlefield limit including the encounter") {
        val support = OtariaEncounterSupport(listOf(
            OtariaReinforcementGroup(legion, OtariaReinforcementCount.Die(4), maximumCopiesOnBattlefield = 4),
        ))
        for (existing in 1..6) {
            repeat(32) { seed ->
                val (plan, _) = OtariaReinforcementPlanner.plan(support, GameRng.seeded(seed.toLong()), mapOf(
                    "Severed Legion" to existing,
                ))
                val roll = plan.rolls.single()
                plan.groups.single().count shouldBe minOf(roll.natural, maxOf(0, 4 - existing))
                roll.countBeforeCopyLimit shouldBe roll.natural
            }
        }
    }

    test("the encountered card can be a fifth copy but receives no extra copies") {
        val support = OtariaEncounterSupport(listOf(
            OtariaReinforcementGroup(legion, OtariaReinforcementCount.Die(4), maximumCopiesOnBattlefield = 4),
        ))
        val (plan, _) = OtariaReinforcementPlanner.plan(support, GameRng.seeded(3), mapOf("Severed Legion" to 5))
        plan.groups.single().count shouldBe 0
        (plan.rolls.single().natural in 1..4) shouldBe true
    }

    test("copy limits account for earlier groups in the same planned entry") {
        val support = OtariaEncounterSupport(listOf(
            OtariaReinforcementGroup(legion, OtariaReinforcementCount.Fixed(2)),
            OtariaReinforcementGroup(legion, OtariaReinforcementCount.Die(4), maximumCopiesOnBattlefield = 4),
        ))
        val (plan, _) = OtariaReinforcementPlanner.plan(support, GameRng.seeded(7), mapOf("Severed Legion" to 1))
        plan.groups.map { it.count } shouldBe listOf(2, 1)
        plan.rolls.size shouldBe 1
    }

    test("invalid content and missing copy counts fail explicitly") {
        shouldThrow<IllegalArgumentException> { OtariaReinforcementCount.Fixed(0) }
        shouldThrow<IllegalArgumentException> { OtariaReinforcementCount.Die(1) }
        shouldThrow<IllegalArgumentException> { OtariaReinforcementCount.Die(4, minimum = -1) }
        shouldThrow<IllegalArgumentException> { OtariaReinforcementCount.Die(4, modifier = Int.MAX_VALUE) }
        shouldThrow<IllegalArgumentException> { OtariaReinforcementContent.Card(" ") }
        shouldThrow<IllegalArgumentException> {
            OtariaReinforcementGroup(goblin, OtariaReinforcementCount.Fixed(1), maximumCopiesOnBattlefield = 4)
        }
        val capped = OtariaEncounterSupport(listOf(
            OtariaReinforcementGroup(legion, OtariaReinforcementCount.Die(4), maximumCopiesOnBattlefield = 4),
        ))
        shouldThrow<IllegalArgumentException> {
            OtariaReinforcementPlanner.plan(capped, GameRng.seeded(1), emptyMap())
        }
        shouldThrow<IllegalArgumentException> {
            OtariaReinforcementPlanner.plan(capped, GameRng.seeded(1), mapOf("Severed Legion" to -1))
        }
    }
})
