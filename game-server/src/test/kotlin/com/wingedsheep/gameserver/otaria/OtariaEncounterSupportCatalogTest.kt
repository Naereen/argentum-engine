package com.wingedsheep.gameserver.otaria

import com.wingedsheep.engine.otaria.OtariaEncounterSupport
import com.wingedsheep.engine.otaria.OtariaExplorationSource
import com.wingedsheep.engine.otaria.OtariaLocation
import com.wingedsheep.engine.otaria.OtariaReinforcementContent
import com.wingedsheep.engine.otaria.OtariaReinforcementCount
import com.wingedsheep.engine.otaria.OtariaReinforcementDestination
import com.wingedsheep.engine.otaria.OtariaReinforcementGroup
import com.wingedsheep.engine.otaria.OtariaReinforcementPlanner
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.model.GameRng
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

class OtariaEncounterSupportCatalogTest : FunSpec({
    fun fixed(value: Int) = OtariaReinforcementCount.Fixed(value)
    fun die(sides: Int, modifier: Int = 0, minimum: Int = 0) =
        OtariaReinforcementCount.Die(sides, modifier, minimum)
    fun cards(name: String, count: Int) =
        OtariaReinforcementGroup(OtariaReinforcementContent.Card(name), fixed(count))
    fun hand(name: String) = OtariaReinforcementGroup(
        OtariaReinforcementContent.Card(name, OtariaReinforcementDestination.HAND), fixed(1),
    )
    fun capped(name: String) =
        OtariaReinforcementGroup(OtariaReinforcementContent.Card(name), die(4), maximumCopiesOnBattlefield = 4)
    fun copies(count: Int) =
        OtariaReinforcementGroup(OtariaReinforcementContent.EncounteredCardCopyToken, fixed(count))
    fun token(color: Color, power: Int, toughness: Int, count: OtariaReinforcementCount, types: Set<Subtype>) =
        OtariaReinforcementGroup(
            OtariaReinforcementContent.CreatureToken(setOf(color), types, power, toughness), count,
        )
    fun support(vararg groups: OtariaReinforcementGroup) = OtariaEncounterSupport(groups.toList())

    val goblins = token(Color.RED, 1, 1, die(4), setOf(Subtype.GOBLIN))
    val blackClerics = token(Color.BLACK, 1, 1, die(4), setOf(Subtype.HUMAN, Subtype.CLERIC))
    val whiteSoldiers = token(Color.WHITE, 1, 1, die(4), setOf(Subtype.SOLDIER))

    test("the five terrain catalogs reproduce every annex entry") {
        val expected = mapOf(
            Color.RED to mapOf(
                "Goblin Sledder" to support(goblins),
                "Goblin Lookout" to support(goblins),
                "Goblin Taskmaster" to support(goblins),
                "Embermage Goblin" to support(cards("Embermage Goblin", 3)),
                "Skirk Outrider" to support(token(Color.GREEN, 3, 3, fixed(1), setOf(Subtype.BEAST))),
                "Blade Sliver" to support(cards("Hunter Sliver", 2)),
                "Pardic Swordsmith" to support(hand("Mountain")),
                "Goblin Warchief" to support(goblins),
            ),
            Color.BLACK to mapOf(
                "Severed Legion" to support(capped("Severed Legion")),
                "Festering Goblin" to support(capped("Festering Goblin")),
                "Vile Deacon" to support(blackClerics),
                "Undead Warchief" to support(token(Color.BLACK, 2, 2, die(2), setOf(Subtype.ZOMBIE))),
                "Dark Supplicant" to support(copies(4), hand("Scion of Darkness")),
                "Cabal Archon" to support(blackClerics),
                "Spectral Sliver" to support(cards("Crypt Sliver", 2)),
                "Noxious Ghoul" to support(token(Color.BLACK, 2, 2, die(4, modifier = -1), setOf(Subtype.ZOMBIE))),
            ),
            Color.GREEN to mapOf(
                "Barkhide Mauler" to support(cards("Barkhide Mauler", 2)),
                "Elven Riders" to support(copies(3)),
                "Root Sliver" to support(cards("Quick Sliver", 2)),
                "Nantuko Mentor" to support(cards("Anurid Barkripper", 1)),
                "Timberwatch Elf" to support(token(Color.GREEN, 1, 1, die(4), setOf(Subtype.ELF, Subtype.WARRIOR))),
            ),
            Color.WHITE to mapOf(
                "Aven Farseer" to OtariaEncounterSupport(faceUpTriggerIgnoresRange = true),
                "Daru Cavalier" to support(cards("Daru Cavalier", 3)),
                "Daru Warchief" to support(whiteSoldiers),
                "Catapult Squad" to support(token(Color.WHITE, 1, 1, die(4, modifier = 1), setOf(Subtype.SOLDIER))),
                "Whipgrass Entangler" to support(token(Color.WHITE, 1, 1, die(4), setOf(Subtype.HUMAN, Subtype.CLERIC))),
                "Resilient Wanderer" to support(hand("Plains")),
                "Ward Sliver" to support(cards("Plated Sliver", 2)),
            ),
            Color.BLUE to mapOf(
                "Screaming Seahawk" to support(cards("Screaming Seahawk", 3)),
                "Cephalid Inkshrouder" to support(hand("Island")),
                "Shifting Sliver" to support(cards("Mistform Sliver", 2)),
            ),
        )
        OtariaEncounterSupportCatalog.colors shouldBe expected
    }

    test("the star catalog reproduces the boss reinforcements") {
        OtariaEncounterSupportCatalog.stars shouldBe mapOf(
            "Essence Sliver" to support(cards("Ward Sliver", 2), cards("Plated Sliver", 2)),
            "Synapse Sliver" to support(cards("Shifting Sliver", 2), cards("Mistform Sliver", 2)),
            "Brood Sliver" to support(cards("Root Sliver", 2), cards("Quick Sliver", 2)),
            "Kamahl, Fist of Krosa" to support(cards("Forest", 6)),
            "Cabal Patriarch" to support(token(Color.BLACK, 2, 2, die(4, minimum = 2), setOf(Subtype.ZOMBIE))),
            "Toxin Sliver" to support(cards("Spectral Sliver", 2), cards("Crypt Sliver", 2)),
            "Magma Sliver" to support(cards("Blade Sliver", 2), cards("Hunter Sliver", 2)),
            "Aboshan, Cephalid Emperor" to support(
                token(Color.BLUE, 1, 1, die(4), setOf(Subtype.OCTOPUS, Subtype.WIZARD)),
            ),
        )
    }

    test("support is scoped to the pile and does not invent a Taskmaster in the red pile") {
        val red = OtariaExplorationSource.Terrain(Color.RED)
        val white = OtariaExplorationSource.Terrain(Color.WHITE)
        val star = OtariaExplorationSource.Star(OtariaLocation.KROSIAN_FOREST)
        OtariaEncounterSupportCatalog.forCard(red, "Goblin Taskmaster") shouldBe support(goblins)
        OtariaEncounterSupportCatalog.forCard(white, "Goblin Taskmaster") shouldBe null
        OtariaEncounterSupportCatalog.forCard(star, "Goblin Taskmaster") shouldBe null
        OtariaEncounterSupportCatalog.forCard(star, "Essence Sliver") shouldBe
            OtariaEncounterSupportCatalog.stars.getValue("Essence Sliver")
        val redPile = OtariaSuppliesCatalog.create().colorPiles.getValue(Color.RED)
        redPile.map { it.name } shouldNotContain "Goblin Taskmaster"
        redPile.size shouldBe 55
    }

    test("the exact boss batch is planned once without recursively expanding reinforcement cards") {
        val support = OtariaEncounterSupportCatalog.stars.getValue("Essence Sliver")
        val rng = GameRng.seeded(3)
        val (plan, next) = OtariaReinforcementPlanner.plan(support, rng, emptyMap())
        plan.groups.map { it.content } shouldBe listOf(
            OtariaReinforcementContent.Card("Ward Sliver"),
            OtariaReinforcementContent.Card("Plated Sliver"),
        )
        plan.groups.map { it.count } shouldBe listOf(2, 2)
        plan.rolls shouldBe emptyList()
        next shouldBe rng
    }

    test("definition diagnostics include support cards but not token types") {
        val names = OtariaEncounterSupportCatalog.referencedCardNames()
        names shouldContain "Goblin Taskmaster"
        names shouldContain "Scion of Darkness"
        names shouldContain "Hunter Sliver"
        names shouldContain "Forest"
        names shouldNotContain "Goblin"
        names shouldNotContain "Zombie"
    }
})
