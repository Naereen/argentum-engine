package com.wingedsheep.engine.otaria

import com.wingedsheep.engine.core.CardsDrawnEvent
import com.wingedsheep.engine.core.ExecutionResult
import com.wingedsheep.engine.core.OtariaStartingLandsPlacedEvent
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.ZoneChangeEvent
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.engine.core.revealsInformation
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.registry.PrintingRegistry
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.LifeTotalComponent
import com.wingedsheep.engine.state.components.identity.PlayerComponent
import com.wingedsheep.engine.state.components.player.MulliganStateComponent
import com.wingedsheep.engine.view.ClientEventTransformer
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.CardEntry
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.model.Printing
import com.wingedsheep.sdk.model.PrintingRef
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.model.ScryfallMetadata
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class OtariaGameInitializerTest : FunSpec({
    val spells = listOf("Rare") + (1..4).map { "Uncommon $it" } + (1..7).map { "Common $it" }
    val names = listOf("Forest", "Forest", "Forest", "Forest", "Island", "Island", "Island", "Island") + spells

    fun registry(): CardRegistry = CardRegistry().also { registry ->
        registry.register(CardDefinition.basicLand("Forest", Subtype.FOREST))
        registry.register(CardDefinition.basicLand("Island", Subtype.ISLAND))
        registry.register(CardDefinition.basicLand("Mountain", Subtype.MOUNTAIN))
        registry.register(CardDefinition.instant("Rare", ManaCost.ZERO, "").copy(
            metadata = ScryfallMetadata(rarity = Rarity.RARE),
        ))
        for (index in 1..4) {
            registry.register(CardDefinition.instant("Uncommon $index", ManaCost.ZERO, "").copy(
                metadata = ScryfallMetadata(rarity = Rarity.UNCOMMON),
            ))
        }
        for (index in 1..8) {
            registry.register(CardDefinition.instant("Common $index", ManaCost.ZERO, ""))
        }
        for (index in 1..4) {
            registry.register(CardDefinition.basicLand("Nonbasic $index", Subtype.FOREST).copy(
                typeLine = TypeLine(cardTypes = setOf(CardType.LAND)),
            ))
        }
    }

    fun supplies(): OtariaSupplies = OtariaSupplies(
        bosses = (1..22).map { "Boss $it" },
        discoveries = 22,
        treasures = (1..21).map { OtariaTreasure.Card("Treasure $it") },
        colorPiles = Color.entries.associateWith { color ->
            List(10) { OtariaPileCard("$color spell $it", OtariaCardKind.NONCREATURE) } +
                List(8) { OtariaPileCard("$color event $it", OtariaCardKind.EVENT) } +
                OtariaPileCard(OtariaSetup.DISCOVERY, OtariaCardKind.DISCOVERY) +
                List(36) { OtariaPileCard("$color creature $it", OtariaCardKind.CREATURE) }
        },
    )

    fun player(
        index: Int,
        deck: Deck = Deck(names),
        lands: List<CardEntry> = listOf(CardEntry("Forest"), CardEntry("Island")),
        kind: OtariaStartingDeckKind = OtariaStartingDeckKind.CONSTRUCTED,
    ): OtariaStartingPlayer = OtariaStartingPlayer(
        PlayerConfig("Player $index", deck, playerId = EntityId("player-$index")),
        lands,
        kind,
    )

    test("players choose two starting lands, then draw five cards at fifteen life without mulligans") {
        val choices = listOf(
            listOf(CardEntry("Forest"), CardEntry("Island")),
            listOf(CardEntry("Forest"), CardEntry("Forest")),
            listOf(CardEntry("Island"), CardEntry("Island")),
            listOf(CardEntry("Island"), CardEntry("Forest")),
            listOf(CardEntry("Forest"), CardEntry("Island")),
        )
        for (count in 3..5) {
            val players = (1..count).map { player(it, lands = choices[it - 1]) }
            val initializer = OtariaGameInitializer(registry())
            val seed = 1234L
            val result = initializer.initialize(players, supplies(), seed)
            initializer.initialize(players, supplies(), seed) shouldBe result
            result.seed shouldBe seed
            val first = OtariaSeating.startingPlayerIndex(count, seed)
            result.state.turnOrder shouldBe result.playerIds.drop(first) + result.playerIds.take(first)
            result.state.activePlayerId shouldBe result.playerIds[first]
            for ((index, playerId) in result.playerIds.withIndex()) {
                result.state.getEntity(playerId)?.get<LifeTotalComponent>()?.life shouldBe 15
                result.state.getEntity(playerId)?.get<PlayerComponent>()?.startingLifeTotal shouldBe 15
                val mulligan = result.state.getEntity(playerId)?.get<MulliganStateComponent>()
                mulligan?.hasKept shouldBe true
                mulligan?.skipped shouldBe true
                val battlefield = result.state.getZone(ZoneKey(playerId, Zone.BATTLEFIELD))
                val hand = result.state.getZone(ZoneKey(playerId, Zone.HAND))
                val library = result.state.getZone(ZoneKey(playerId, Zone.LIBRARY))
                battlefield.size shouldBe 2
                hand.size shouldBe 5
                library.size shouldBe 13
                (battlefield + hand + library).distinct().size shouldBe 20
                battlefield.map { result.state.getEntity(it)?.get<CardComponent>()?.name } shouldBe
                    choices[index].map { it.name }
                requireNotNull(result.state.otaria).players.getValue(playerId).reserve shouldBe emptyList()
            }
            val placements = result.events.filterIsInstance<OtariaStartingLandsPlacedEvent>()
            placements.size shouldBe count
            placements.all { it.revealsInformation } shouldBe true
            val firstDrawIndex = result.events.indexOfFirst { it is ZoneChangeEvent && it.toZone == Zone.HAND }
            result.events.indexOfLast { it is OtariaStartingLandsPlacedEvent } shouldBe (firstDrawIndex - 1)
            result.events.filterIsInstance<CardsDrawnEvent>().map { it.count } shouldBe List(count) { 5 }
            ClientEventTransformer.transform(placements, result.playerIds.first(), result.state) shouldBe emptyList()
            val json = Json { serializersModule = engineSerializersModule; allowStructuredMapKeys = true; encodeDefaults = true }
            val execution = ExecutionResult.success(result.state, result.events)
            json.decodeFromString<ExecutionResult>(json.encodeToString(execution)) shouldBe execution
        }
    }

    test("constructed decks and land choices are validated before initialization") {
        val initializer = OtariaGameInitializer(registry())
        val valid = listOf(player(1), player(2), player(3))
        val invalidFirst = listOf(
            player(1, deck = Deck(names.dropLast(1))),
            player(1, deck = Deck(names.dropLast(1) + "Common 1")),
            player(1, deck = Deck(names.map { if (it == "Rare") "Common 8" else it })),
            player(
                1,
                deck = Deck((1..4).map { "Nonbasic $it" } + names.drop(4)),
                lands = listOf(CardEntry("Island"), CardEntry("Island")),
            ),
            player(1, lands = listOf(CardEntry("Forest"))),
            player(1, lands = listOf(CardEntry("Forest"), CardEntry("Mountain"))),
            player(1, lands = listOf(CardEntry("Rare"), CardEntry("Island"))),
            player(1, deck = Deck(names, commander = "Rare")),
        )
        for (first in invalidFirst) {
            shouldThrow<IllegalArgumentException> {
                initializer.initialize(listOf(first) + valid.drop(1), supplies(), 42)
            }
        }
        shouldThrow<IllegalArgumentException> {
            initializer.initialize(listOf(player(1), player(1), player(3)), supplies(), 42)
        }
        val collidingId = player(1).let { it.copy(player = it.player.copy(playerId = EntityId("e1000"))) }
        shouldThrow<IllegalArgumentException> {
            initializer.initialize(listOf(collidingId, player(2), player(3)), supplies(), 42)
        }
        shouldThrow<IllegalArgumentException> {
            initializer.initialize(valid.take(2), supplies(), 42)
        }
    }

    test("a host-verified Jumpstart deck still requires twenty cards and two chosen basic lands") {
        val initializer = OtariaGameInitializer(registry())
        val jumpstart = player(
            1,
            deck = Deck(listOf("Forest", "Island") + List(18) { "Common 1" }),
            kind = OtariaStartingDeckKind.JUMPSTART,
        )
        val result = initializer.initialize(listOf(jumpstart, player(2), player(3)), supplies(), 42)
        result.state.getZone(ZoneKey(result.playerIds.first(), Zone.BATTLEFIELD)).size shouldBe 2
        val badSelection = jumpstart.copy(startingBasicLands = listOf(CardEntry("Forest"), CardEntry("Forest")))
        shouldThrow<IllegalArgumentException> {
            initializer.initialize(listOf(badSelection, player(2), player(3)), supplies(), 42)
        }
        val short = jumpstart.copy(player = jumpstart.player.copy(deck = Deck(listOf("Forest", "Island"))))
        shouldThrow<IllegalArgumentException> {
            initializer.initialize(listOf(short, player(2), player(3)), supplies(), 42)
        }
    }

    test("separate stock basics leave all twenty Jumpstart cards available to draw and emit placement events") {
        val initializer = OtariaGameInitializer(registry())
        val first = player(
            1, deck = Deck(List(20) { "Common 1" }),
            lands = listOf(CardEntry("Forest"), CardEntry("Island")),
            kind = OtariaStartingDeckKind.JUMPSTART,
        ).copy(startingLandSource = OtariaStartingLandSource.SEPARATE_STOCK)
        val result = initializer.initialize(listOf(first, player(2), player(3)), supplies(), 42)
        val id = result.playerIds.first()
        val battlefield = result.state.getZone(ZoneKey(id, Zone.BATTLEFIELD))
        battlefield.map { result.state.getEntity(it)?.get<CardComponent>()?.name } shouldBe listOf("Forest", "Island")
        result.state.getZone(ZoneKey(id, Zone.HAND)).size shouldBe 5
        result.state.getZone(ZoneKey(id, Zone.LIBRARY)).size shouldBe 15
        result.events.filterIsInstance<OtariaStartingLandsPlacedEvent>().first().cardIds shouldBe battlefield
        result.state.getZone(ZoneKey(result.playerIds[1], Zone.LIBRARY)).size shouldBe 13
        shouldThrow<IllegalArgumentException> {
            initializer.initialize(listOf(first.copy(deckKind = OtariaStartingDeckKind.CONSTRUCTED), player(2), player(3)), supplies(), 42)
        }
        shouldThrow<IllegalArgumentException> {
            initializer.initialize(listOf(first.copy(startingBasicLands = listOf(CardEntry("Rare"), CardEntry("Island"))), player(2), player(3)), supplies(), 42)
        }
        shouldThrow<IllegalArgumentException> {
            initializer.initialize(listOf(first.copy(startingBasicLands = listOf(CardEntry("Forest"))), player(2), player(3)), supplies(), 42)
        }
    }

    test("chosen land printings are preserved and spell rarity uses the chosen printing") {
        val cards = registry()
        val printings = PrintingRegistry()
        val selectedForest = Printing(
            oracleId = "forest", name = "Forest", setCode = "TST", collectorNumber = "1",
            imageUri = "https://example.invalid/selected.png",
        )
        val otherForest = selectedForest.copy(collectorNumber = "2", imageUri = "https://example.invalid/other.png")
        val mythic = Printing(
            oracleId = "rare", name = "Rare", setCode = "TST", collectorNumber = "3", rarity = Rarity.MYTHIC,
        )
        printings.register(listOf(selectedForest, otherForest, mythic))
        val entries = names.mapIndexed { index, name ->
            when (index) {
                0 -> CardEntry(name, selectedForest.ref)
                1 -> CardEntry(name, otherForest.ref)
                8 -> CardEntry(name, mythic.ref)
                else -> CardEntry(name)
            }
        }
        val choice = listOf(CardEntry("Forest", selectedForest.ref), CardEntry("Island"))
        val first = player(1, deck = Deck.fromEntries(entries), lands = choice)
        val result = OtariaGameInitializer(cards, printings).initialize(listOf(first, player(2), player(3)), supplies(), 71)
        val battlefield = result.state.getZone(ZoneKey(result.playerIds.first(), Zone.BATTLEFIELD))
        val forest = result.state.getEntity(battlefield.first())?.get<CardComponent>()
        forest?.imageUri shouldBe selectedForest.imageUri
        forest?.imageUri shouldNotBe otherForest.imageUri

        val wrongRarity = mythic.copy(rarity = Rarity.COMMON)
        printings.register(wrongRarity)
        shouldThrow<IllegalArgumentException> {
            OtariaGameInitializer(cards, printings).initialize(listOf(first, player(2), player(3)), supplies(), 71)
        }
        printings.register(mythic)
        val missing = first.copy(startingBasicLands = listOf(CardEntry("Forest", PrintingRef("TST", "99")), CardEntry("Island")))
        shouldThrow<IllegalArgumentException> {
            OtariaGameInitializer(cards, printings).initialize(listOf(missing, player(2), player(3)), supplies(), 71)
        }
        val unknownEntries = entries.toMutableList().apply { set(0, CardEntry("Forest", PrintingRef("TST", "99"))) }
        val unknown = player(1, deck = Deck.fromEntries(unknownEntries), lands = listOf(unknownEntries[0], CardEntry("Island")))
        shouldThrow<IllegalArgumentException> {
            OtariaGameInitializer(cards, printings).initialize(listOf(unknown, player(2), player(3)), supplies(), 71)
        }
    }
})
