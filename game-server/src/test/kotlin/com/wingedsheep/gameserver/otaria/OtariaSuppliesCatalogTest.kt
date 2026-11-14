package com.wingedsheep.gameserver.otaria

import com.wingedsheep.engine.otaria.OtariaCardKind
import com.wingedsheep.engine.otaria.OtariaEventCopyRule
import com.wingedsheep.engine.otaria.OtariaQuotas
import com.wingedsheep.engine.otaria.OtariaSetup
import com.wingedsheep.engine.otaria.OtariaTreasure
import com.wingedsheep.engine.otaria.OtariaTreasureToken
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.PlayerComponent
import com.wingedsheep.mtg.sets.MtgSetCatalog
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

class OtariaSuppliesCatalogTest : FunSpec({
    test("the supply includes the confirmed 22nd boss and all five exact color piles") {
        val supplies = OtariaSuppliesCatalog.create()
        supplies.bosses.size shouldBe 22
        supplies.bosses.distinct().size shouldBe 22
        supplies.bosses shouldContain "Aboshan, Cephalid Emperor"
        supplies.bosses shouldContain "Kamahl, Pit Fighter"
        supplies.bosses shouldContain "Sliver Overlord"
        supplies.discoveries shouldBe 22
        supplies.colorPiles.keys shouldBe Color.entries.toSet()

        val duplicates = mapOf(
            Color.GREEN to setOf("Leaf Dancer", "Nantuko Vigilante", "Patron of the Wild"),
            Color.RED to setOf(
                "Barbarian Lunatic", "Crested Craghorn", "Dwarven Grunt", "Goblin Lookout",
                "Skirk Marauder", "Skirk Outrider",
            ),
            Color.BLACK to setOf("Festering Goblin", "Skinthinner"),
            Color.BLUE to setOf("Echo Tracer", "Keeneye Aven", "Mercurial Kite", "Mirror Wall", "Thought Nibbler"),
            Color.WHITE to setOf("Daru Lancer", "Gustcloak Harrier", "Lowland Tracker", "Zealous Inquisitor"),
        )
        for ((color, pile) in supplies.colorPiles) {
            pile.size shouldBe 55
            pile.count { it.kind == OtariaCardKind.NONCREATURE } shouldBe 10
            pile.count { it.kind == OtariaCardKind.CREATURE } shouldBe 36
            pile.count { it.kind == OtariaCardKind.EVENT } shouldBe 8
            pile.count { it.kind == OtariaCardKind.DISCOVERY && it.name == OtariaSetup.DISCOVERY } shouldBe 1
            val counts = pile.filter { it.kind == OtariaCardKind.CREATURE }.groupingBy { it.name }.eachCount()
            counts.values.all { it in 1..2 } shouldBe true
            counts.filterValues { it == 2 }.keys shouldBe duplicates.getValue(color)
            pile.filter { it.kind == OtariaCardKind.EVENT }.map { it.name }.distinct().size shouldBe 6
        }
    }

    test("the treasure supply distinguishes real cards from scrap and rock tokens") {
        val treasures = OtariaSuppliesCatalog.create().treasures
        val cards = treasures.filterIsInstance<OtariaTreasure.Card>().groupingBy { it.name }.eachCount()
        cards shouldBe mapOf(
            "Ark of Blight" to 2, "Dream Chisel" to 1, "Proteus Machine" to 2,
            "Shadowblood Egg" to 1, "Skycloud Egg" to 1, "Mossfire Egg" to 1,
            "Sungrass Egg" to 1, "Darkwater Egg" to 1, "Cryptic Gateway" to 1,
            "Riptide Replicator" to 1, "Stabilizer" to 1, "Slate of Ancestry" to 1,
        )
        treasures.count { it == OtariaTreasure.Token(OtariaTreasureToken.SCRAP) } shouldBe 4
        treasures.count { it == OtariaTreasure.Token(OtariaTreasureToken.ROCK) } shouldBe 5
        treasures.size shouldBe 23
        treasures shouldNotContain OtariaTreasure.Card(OtariaSetup.MIRARI)
    }

    test("implemented event proxies carry their effects into the exploration supply") {
        val events = OtariaSuppliesCatalog.create().colorPiles.values.flatten()
            .filter { it.kind == OtariaCardKind.EVENT }
        val supported = setOf(
            "Ev. Naturalisation", "Ev. Frappe Concertée", "Ev. Infestation",
            "Ev. Pyroclasme", "Ev. Blocage cérébral",
        )
        events.filter { it.eventEffect != null }.map { it.name }.toSet() shouldBe supported
        events.filter { it.name in supported }.all { it.eventEffect != null } shouldBe true
        events.count { it.eventEffect != null } shouldBe 10
        events.filter { it.eventCopyRule != null }.map { it.name }.toSet() shouldBe setOf("Ev. Blocage cérébral")
        events.filter { it.name == "Ev. Blocage cérébral" }
            .all { it.eventCopyRule == OtariaEventCopyRule.EACH_SPELL_CAST_THIS_TURN } shouldBe true
    }

    test("the supplied catalog initializes all supported player counts") {
        for (count in 3..5) {
            val players = (1..count).map { EntityId("player-$it") }
            val initial = GameState(
                entities = players.associateWith { ComponentContainer.of(PlayerComponent(it.value)) },
                turnOrder = players,
            )
            val mode = requireNotNull(OtariaSetup.initialize(initial, OtariaSuppliesCatalog.create()).state.otaria)
            mode.encounterSupport shouldBe OtariaEncounterSupportCatalog.table
            val quota = OtariaQuotas.forPlayers(count)
            mode.starPile.cards.count { it.kind == OtariaCardKind.BOSS } shouldBe quota.bosses
            if (count == 5) {
                mode.starPile.cards.count { it.kind == OtariaCardKind.BOSS && it.name == "Kamahl, Pit Fighter" } shouldBe 1
                mode.starPile.cards.filter { it.kind == OtariaCardKind.BOSS }.map { it.name }.distinct().size shouldBe 22
            }
            mode.starPile.cards.count { it.kind == OtariaCardKind.DISCOVERY } shouldBe quota.discoveries
            mode.treasurePile.size shouldBe quota.otherTreasures + 1
            mode.treasurePile.count { it == OtariaTreasure.Card(OtariaSetup.MIRARI) } shouldBe 1
        }
    }

    test("missing definitions are explicit and exclude event proxies and reserve tokens") {
        val registry = CardRegistry().also { it.register(CardDefinition.basicLand("Forest", Subtype.FOREST)) }
        val missing = OtariaSuppliesCatalog.missingCardDefinitions(registry)
        missing shouldContain "Mirari"
        missing shouldContain "Aboshan, Cephalid Emperor"
        missing shouldContain "Dream Chisel"
        missing shouldContain "Scion of Darkness"
        missing shouldContain "Goblin Taskmaster"
        missing shouldNotContain "Forest"
        missing.none { it.startsWith("Ev.") || it == "SCRAP" || it == "ROCK" } shouldBe true
        missing shouldBe missing.distinct().sorted()
    }

    test("implemented corpus cards have the types assigned by the annex") {
        val registry = CardRegistry().apply {
            MtgSetCatalog.all.forEach { register(it.cards + it.basicLands) }
        }
        val references = OtariaSuppliesCatalog.create().colorPiles.values.flatten()
            .filter { it.kind == OtariaCardKind.CREATURE || it.kind == OtariaCardKind.NONCREATURE }
        val implemented = references.mapNotNull { card ->
            registry.getCard(card.name)?.let { card to it }
        }
        (implemented.size > 0) shouldBe true
        for ((entry, card) in implemented) {
            card.typeLine.isCreature shouldBe (entry.kind == OtariaCardKind.CREATURE)
            card.typeLine.isLand shouldBe false
        }
    }
})
