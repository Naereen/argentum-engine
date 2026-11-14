package com.wingedsheep.engine.otaria

import com.wingedsheep.engine.core.CardEntityFactory
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.mechanics.layers.Layer
import com.wingedsheep.engine.mechanics.layers.SerializableModification
import com.wingedsheep.engine.mechanics.layers.addFloatingEffect
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.FaceDownComponent
import com.wingedsheep.engine.state.components.identity.PlayerComponent
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.model.GameRng
import com.wingedsheep.sdk.scripting.Duration
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class OtariaContestPlannerTest : FunSpec({
    val explorer = EntityId("explorer")
    val encounter = EntityId("encounter")
    val creature = EntityId("creature")

    fun battlefield(haste: Boolean = false, faceDown: Boolean = false): GameState {
        val definition = CardDefinition.creature(
            name = "Encounter creature",
            manaCost = ManaCost.ZERO,
            subtypes = setOf(Subtype("Beast")),
            power = 2,
            toughness = 2,
            keywords = if (haste) setOf(Keyword.HASTE) else emptySet(),
        )
        var card = CardEntityFactory.create(definition, encounter)
        if (faceDown) card = card.with(FaceDownComponent)
        return GameState(
            entities = mapOf(
                explorer to ComponentContainer.of(PlayerComponent("Explorer")),
                encounter to ComponentContainer.of(PlayerComponent("Encounter")),
                creature to card,
            ),
            turnOrder = listOf(explorer, encounter),
            activePlayerId = explorer,
            rng = GameRng.seeded(73),
        ).addToZone(ZoneKey(encounter, Zone.BATTLEFIELD), creature)
    }

    test("haste gives the encounter initiative without consuming random numbers") {
        val initial = battlefield(haste = true)
        val (plan, next) = OtariaContestPlanner.initiative(initial, creature)
        plan.first shouldBe OtariaInitiativeSide.ENCOUNTER
        plan.contest shouldBe null
        next shouldBe initial.rng
    }

    test("initiative reads projected haste, including face-down and changed abilities") {
        val faceDown = battlefield(haste = true, faceDown = true)
        faceDown.projectedState.hasKeyword(creature, Keyword.HASTE) shouldBe false
        val (normal, advanced) = OtariaContestPlanner.initiative(faceDown, creature)
        (normal.contest != null) shouldBe true
        (advanced != faceDown.rng) shouldBe true

        val granted = faceDown.addFloatingEffect(
            layer = Layer.ABILITY,
            modification = SerializableModification.GrantKeyword(Keyword.HASTE.name),
            affectedEntities = setOf(creature),
            duration = Duration.EndOfTurn,
            context = EffectContext(creature, encounter),
        )
        granted.projectedState.hasKeyword(creature, Keyword.HASTE) shouldBe true
        val (automatic, unchanged) = OtariaContestPlanner.initiative(granted, creature)
        automatic.first shouldBe OtariaInitiativeSide.ENCOUNTER
        automatic.contest shouldBe null
        unchanged shouldBe granted.rng

        val removed = battlefield(haste = true).addFloatingEffect(
            layer = Layer.ABILITY,
            modification = SerializableModification.RemoveKeyword(Keyword.HASTE.name),
            affectedEntities = setOf(creature),
            duration = Duration.EndOfTurn,
            context = EffectContext(creature, encounter),
        )
        removed.projectedState.hasKeyword(creature, Keyword.HASTE) shouldBe false
        (OtariaContestPlanner.initiative(removed, creature).first.contest != null) shouldBe true
    }

    test("initiative requires a creature currently on the battlefield") {
        val initial = battlefield()
        val offBattlefield = initial.removeFromZone(ZoneKey(encounter, Zone.BATTLEFIELD), creature)
        shouldThrow<IllegalArgumentException> { OtariaContestPlanner.initiative(offBattlefield, creature) }
        shouldThrow<IllegalArgumentException> { OtariaContestPlanner.initiative(initial, explorer) }
    }

    test("ties are rerolled with two fresh D6s until one side wins") {
        val opponent = EntityId("opponent")
        val seed = (0L..1000L).first { candidate ->
            OtariaContestPlanner.retreat(GameRng.seeded(candidate), listOf(opponent))
                .first.contests.single().contest.rounds.size >= 3
        }
        val original = GameRng.seeded(seed)
        val (plan, advanced) = OtariaContestPlanner.retreat(original, listOf(opponent))
        val contest = plan.contests.single().contest
        contest.rounds.dropLast(1).all { it.explorer == it.opponent } shouldBe true
        (contest.rounds.last().explorer != contest.rounds.last().opponent) shouldBe true

        var expected = original
        for (round in contest.rounds) {
            val (first, afterFirst) = expected.nextInt(6)
            val (second, afterSecond) = afterFirst.nextInt(6)
            round shouldBe OtariaContestRound(first + 1, second + 1)
            expected = afterSecond
        }
        advanced shouldBe expected
        plan.succeeds shouldBe contest.explorerWon
        OtariaContestPlanner.retreat(original, listOf(opponent)) shouldBe (plan to advanced)
        Json.decodeFromString<OtariaRetreatPlan>(Json.encodeToString(plan)) shouldBe plan
    }

    test("a retreat independently contests every blocking opponent and requires every win") {
        val opponents = listOf(EntityId("boss"), EntityId("player"), EntityId("reinforcement"))
        var sawSuccess = false
        var sawFailureBeforeLaterContest = false
        repeat(256) { seed ->
            val (plan, advanced) = OtariaContestPlanner.retreat(GameRng.seeded(seed.toLong()), opponents)
            plan.contests.map { it.opponentId } shouldBe opponents
            plan.succeeds shouldBe plan.contests.all { it.contest.explorerWon }
            if (plan.succeeds) sawSuccess = true
            if (!plan.contests.first().contest.explorerWon && plan.contests.size == opponents.size) {
                sawFailureBeforeLaterContest = true
            }

            var expected = GameRng.seeded(seed.toLong())
            for (contest in plan.contests) {
                for (round in contest.contest.rounds) {
                    val (first, afterFirst) = expected.nextInt(6)
                    val (second, afterSecond) = afterFirst.nextInt(6)
                    round shouldBe OtariaContestRound(first + 1, second + 1)
                    expected = afterSecond
                }
            }
            advanced shouldBe expected
        }
        sawSuccess shouldBe true
        sawFailureBeforeLaterContest shouldBe true
    }

    test("unopposed retreat uses no randomness and invalid contests are rejected") {
        val rng = GameRng.seeded(8)
        OtariaContestPlanner.retreat(rng, emptyList()) shouldBe (OtariaRetreatPlan(emptyList()) to rng)
        shouldThrow<IllegalArgumentException> {
            OtariaContestPlanner.retreat(rng, listOf(creature, creature))
        }
        shouldThrow<IllegalArgumentException> { OtariaContestRound(0, 3) }
        shouldThrow<IllegalArgumentException> { OtariaContest(emptyList()) }
        shouldThrow<IllegalArgumentException> { OtariaContest(listOf(OtariaContestRound(2, 2))) }
        shouldThrow<IllegalArgumentException> {
            OtariaContest(listOf(OtariaContestRound(4, 3), OtariaContestRound(6, 1)))
        }
        shouldThrow<IllegalArgumentException> {
            OtariaInitiativePlan(OtariaInitiativeSide.EXPLORER, null)
        }
    }
})
