package com.wingedsheep.engine.otaria

import com.wingedsheep.engine.core.OtariaSetupEvent
import com.wingedsheep.engine.core.ExecutionResult
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.PlayerComponent
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.model.GameRng
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class OtariaSetupTest : FunSpec({
    fun game(playerCount: Int, seed: Long = 1): GameState {
        val players = (1..playerCount).map { EntityId("player-$it") }
        return GameState(
            entities = players.associateWith { ComponentContainer.of(PlayerComponent(it.value)) },
            turnOrder = players,
            rng = GameRng.seeded(seed),
        )
    }

    fun supplies(): OtariaSupplies = OtariaSupplies(
        bosses = (1..22).map { "Boss $it" },
        discoveries = 22,
        treasures = (1..23).map { OtariaTreasure.Card("Treasure $it") },
        colorPiles = Color.entries.associateWith { color ->
            List(10) { OtariaPileCard("$color spell $it", OtariaCardKind.NONCREATURE) } +
                List(8) { OtariaPileCard("$color event $it", OtariaCardKind.EVENT) } +
                OtariaPileCard("Discovery", OtariaCardKind.DISCOVERY) +
                List(36) { OtariaPileCard("$color creature $it", OtariaCardKind.CREATURE) }
        },
    )

    test("the map has the eight distinct stars and six-neighbour hex geometry") {
        OtariaBoard.tiles.size shouldBe 189
        OtariaBoard.tiles.values.mapNotNull { it.location } shouldContainExactlyInAnyOrder OtariaLocation.entries
        for (location in OtariaLocation.entries) {
            OtariaBoard.tiles.getValue(location.position).location shouldBe location
        }
        val terrainByLocation = mapOf(
            OtariaLocation.KROSIAN_FOREST to Color.GREEN,
            OtariaLocation.ORDER_ENCAMPMENT to Color.WHITE,
            OtariaLocation.SOUTHERN_PIT_ARENA to Color.BLACK,
            OtariaLocation.BALSHAN_BAY to Color.BLUE,
            OtariaLocation.PARDIC_MOUNTAINS to Color.RED,
            OtariaLocation.ORDER_SOUTHERN_ENCAMPMENT to Color.WHITE,
            OtariaLocation.CABAL_CITY to Color.BLACK,
            OtariaLocation.CEPHALID_SOUTHERN_PALACE to Color.BLUE,
        )
        terrainByLocation.forEach { (location, color) ->
            OtariaBoard.tiles.getValue(location.position).color shouldBe color
        }
        val centre = OtariaPosition(6, 7)
        OtariaBoard.neighbours(centre).size shouldBe 6
        for (neighbour in OtariaBoard.neighbours(centre)) {
            OtariaBoard.distance(centre, neighbour) shouldBe 1L
            OtariaBoard.neighbours(neighbour).contains(centre) shouldBe true
        }
        for (position in OtariaBoard.tiles.keys) {
            val neighbours = OtariaBoard.neighbours(position)
            neighbours.size shouldBe neighbours.distinct().size
            for (neighbour in neighbours) {
                OtariaBoard.distance(position, neighbour) shouldBe 1L
                OtariaBoard.neighbours(neighbour).contains(position) shouldBe true
            }
            val atMostTwoSteps = neighbours.toSet() + neighbours.flatMap(OtariaBoard::neighbours) + position
            for (destination in OtariaBoard.tiles.keys) {
                (destination in atMostTwoSteps) shouldBe (OtariaBoard.distance(position, destination) <= 2L)
            }
        }
        shouldThrow<IllegalArgumentException> { OtariaBoard.neighbours(OtariaPosition(-1, 0)) }
    }

    test("setup applies each exact player-count quota without sharing starts or piles") {
        for (count in 3..5) {
            val initial = game(count)
            val result = OtariaSetup.initialize(initial, supplies())
            val mode = requireNotNull(result.state.otaria)
            val quota = OtariaQuotas.forPlayers(count)
            mode.starPile.cards.count { it.kind == OtariaCardKind.BOSS } shouldBe quota.bosses
            mode.starPile.cards.count { it.kind == OtariaCardKind.DISCOVERY } shouldBe quota.discoveries
            mode.starPile.discard shouldBe emptyList()
            mode.treasurePile.size shouldBe quota.otherTreasures + 1
            mode.treasurePile.count { it == OtariaTreasure.Card(OtariaSetup.MIRARI) } shouldBe 1
            mode.locationPile.groupingBy { it }.eachCount() shouldBe OtariaLocation.entries.associateWith { 3 }
            mode.players.keys shouldBe initial.turnOrder.toSet()
            mode.players.values.map { it.startingLocation }.distinct().size shouldBe count
            mode.players.values.all { it.position == it.startingLocation.position } shouldBe true
            mode.colorPiles.keys shouldBe Color.entries.toSet()
            for (pile in mode.colorPiles.values) {
                pile.cards.size shouldBe 55
                pile.cards.count { it.kind == OtariaCardKind.NONCREATURE } shouldBe 10
                pile.cards.count { it.kind == OtariaCardKind.EVENT } shouldBe 8
                pile.cards.count { it.kind == OtariaCardKind.DISCOVERY } shouldBe 1
                pile.cards.count { it.kind == OtariaCardKind.CREATURE } shouldBe 36
                pile.discard shouldBe emptyList()
            }
            mode.explorationsUsed.values.all { it == 0 } shouldBe true
            result.events.single() shouldBe OtariaSetupEvent(mode.players.mapValues { it.value.startingLocation })
            initial.otaria shouldBe null
            if (count == 5) {
                val bosses = mode.starPile.cards.filter { it.kind == OtariaCardKind.BOSS }.map { it.name }
                bosses.distinct().size shouldBe 22
            }
        }
    }

    test("a supplied duplicate remains a physical copy, rather than being invented by setup") {
        val supply = supplies()
        val physicalBosses = supply.bosses.dropLast(1) + supply.bosses.first()
        val result = OtariaSetup.initialize(game(5), supply.copy(bosses = physicalBosses))
        val selected = requireNotNull(result.state.otaria).starPile.cards
            .filter { it.kind == OtariaCardKind.BOSS }.map { it.name }
        selected shouldContainExactlyInAnyOrder physicalBosses
    }

    test("all eight limits match the player-count table") {
        val limits = mapOf(
            OtariaLocation.KROSIAN_FOREST to listOf(4, 4, 6),
            OtariaLocation.ORDER_ENCAMPMENT to listOf(2, 2, 4),
            OtariaLocation.SOUTHERN_PIT_ARENA to listOf(7, 9, 11),
            OtariaLocation.BALSHAN_BAY to listOf(2, 3, 4),
            OtariaLocation.PARDIC_MOUNTAINS to listOf(2, 4, 4),
            OtariaLocation.ORDER_SOUTHERN_ENCAMPMENT to listOf(2, 3, 4),
            OtariaLocation.CABAL_CITY to listOf(2, 4, 6),
            OtariaLocation.CEPHALID_SOUTHERN_PALACE to listOf(2, 2, 4),
        )
        for ((location, expected) in limits) {
            (3..5).map(location::explorationLimit) shouldBe expected
        }
    }

    test("setup is reproducible and survives a GameState serialization round trip") {
        val initial = game(5, 1234)
        val result = OtariaSetup.initialize(initial, supplies())
        OtariaSetup.initialize(initial, supplies()) shouldBe result
        result.state.rng shouldNotBe initial.rng
        OtariaSetup.initialize(game(5, 1235), supplies()).state.otaria shouldNotBe result.state.otaria
        val json = Json {
            serializersModule = engineSerializersModule
            allowStructuredMapKeys = true
            encodeDefaults = true
        }
        json.decodeFromString<GameState>(json.encodeToString(result.state)) shouldBe result.state
        json.decodeFromString<ExecutionResult>(json.encodeToString(result)) shouldBe result
        val ordinaryStateJson = json.encodeToString(initial)
        json.parseToJsonElement(ordinaryStateJson).jsonObject.containsKey("otaria") shouldBe false
        json.decodeFromString<GameState>(ordinaryStateJson) shouldBe initial
    }

    test("setup does not retain mutable input supplies") {
        val original = supplies()
        val bosses = original.bosses.toMutableList()
        val treasures = original.treasures.toMutableList()
        val locations = original.locations.toMutableList()
        val bluePile = original.colorPiles.getValue(Color.BLUE).toMutableList()
        val colorPiles = original.colorPiles.toMutableMap().apply { put(Color.BLUE, bluePile) }
        val tokenColors = mutableSetOf(Color.GREEN)
        val tokenSubtypes = mutableSetOf(Subtype.ELF)
        val groups = mutableListOf(OtariaReinforcementGroup(
            OtariaReinforcementContent.CreatureToken(tokenColors, tokenSubtypes, 1, 1),
            OtariaReinforcementCount.Fixed(1),
        ))
        val rules = mutableMapOf("A creature" to OtariaEncounterSupport(groups))
        val supportColors = mutableMapOf(Color.GREEN to rules)
        val starRules = mutableMapOf("A boss" to OtariaEncounterSupport(groups))
        val input = original.copy(
            bosses = bosses,
            treasures = treasures,
            locations = locations,
            colorPiles = colorPiles,
            encounterSupport = OtariaEncounterSupportTable(supportColors, starRules),
        )
        val result = OtariaSetup.initialize(game(5), input)
        val json = Json { serializersModule = engineSerializersModule; allowStructuredMapKeys = true }
        val before = json.encodeToString(result.state)
        bosses.clear()
        treasures.clear()
        locations.clear()
        bluePile.clear()
        colorPiles.clear()
        tokenColors.clear()
        tokenSubtypes.clear()
        groups.clear()
        rules.clear()
        supportColors.clear()
        starRules.clear()
        json.encodeToString(result.state) shouldBe before
    }

    test("invalid player counts, supplies, duplicate Mirari, and repeated setup fail explicitly") {
        shouldThrow<IllegalArgumentException> { OtariaSetup.initialize(game(2), supplies()) }
        shouldThrow<IllegalArgumentException> { OtariaSetup.initialize(game(6), supplies()) }
        shouldThrow<IllegalArgumentException> {
            OtariaSetup.initialize(game(5), supplies().copy(bosses = supplies().bosses.take(21)))
        }
        shouldThrow<IllegalArgumentException> {
            OtariaSetup.initialize(game(3), supplies().copy(treasures = supplies().treasures + OtariaTreasure.Card("mirari")))
        }
        shouldThrow<IllegalArgumentException> {
            OtariaSetup.initialize(game(3), supplies().copy(treasures = supplies().treasures + OtariaTreasure.Card(" Mirari ")))
        }
        shouldThrow<IllegalArgumentException> {
            OtariaSetup.initialize(game(3), supplies().copy(bosses = supplies().bosses + " Boss"))
        }
        shouldThrow<IllegalArgumentException> {
            OtariaSetup.initialize(game(3), supplies().copy(colorPiles = supplies().colorPiles - Color.BLUE))
        }
        shouldThrow<IllegalArgumentException> {
            OtariaSetup.initialize(game(3), supplies().copy(locations = listOf(OtariaLocation.KROSIAN_FOREST)))
        }
        shouldThrow<IllegalArgumentException> {
            OtariaSetup.initialize(
                game(3),
                supplies().copy(locations = OtariaLocation.entries.flatMap { listOf(it, it) }),
            )
        }
        shouldThrow<IllegalArgumentException> {
            OtariaSetup.initialize(game(3), supplies().copy(locations = supplies().locations + OtariaLocation.KROSIAN_FOREST))
        }
        val malformed = supplies().colorPiles.getValue(Color.BLUE).dropLast(1)
        shouldThrow<IllegalArgumentException> {
            OtariaSetup.initialize(game(3), supplies().copy(colorPiles = supplies().colorPiles + (Color.BLUE to malformed)))
        }
        val withoutDiscovery = supplies().colorPiles.getValue(Color.BLUE).map {
            if (it.kind == OtariaCardKind.DISCOVERY) it.copy(kind = OtariaCardKind.EVENT) else it
        }
        shouldThrow<IllegalArgumentException> {
            OtariaSetup.initialize(game(3), supplies().copy(colorPiles = supplies().colorPiles + (Color.BLUE to withoutDiscovery)))
        }
        val badName = supplies().colorPiles.getValue(Color.BLUE).toMutableList()
        badName[0] = badName[0].copy(name = " Card ")
        shouldThrow<IllegalArgumentException> {
            OtariaSetup.initialize(game(3), supplies().copy(colorPiles = supplies().colorPiles + (Color.BLUE to badName)))
        }
        val initialized = OtariaSetup.initialize(game(3), supplies()).state
        shouldThrow<IllegalArgumentException> { OtariaSetup.initialize(initialized, supplies()) }
    }
})
