package com.wingedsheep.engine.otaria

import com.wingedsheep.engine.core.OtariaCardSkippedEvent
import com.wingedsheep.engine.core.OtariaEventCopiesCreatedEvent
import com.wingedsheep.engine.core.OtariaEventCopyFinishedEvent
import com.wingedsheep.engine.core.OtariaEventFinishedEvent
import com.wingedsheep.engine.core.OtariaExplorationStartedEvent
import com.wingedsheep.engine.view.ClientEvent
import com.wingedsheep.engine.view.ClientEventTransformer
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class OtariaEventPresentationTest : FunSpec({
    val explorer = EntityId("explorer")
    val manager = EntityId("manager")
    val source = OtariaExplorationSource.Terrain(Color.GREEN)
    val transformer = ClientEventTransformer

    test("all supplied event proxies have local artwork, separate from ordinary spell artwork") {
        val images = mapOf(
            "Ev. Naturalisation" to "naturalisation",
            "Ev. Frappe Concertée" to "frappe-concertee",
            "Ev. Infestation" to "infestation",
            "Ev. Pyroclasme" to "pyroclasme",
            "Ev. Blocage cérébral" to "blocage-cerebral",
            "Ev. Flux du jusant" to "flux-du-jusant",
            "Ev. Marchant itinérant U5" to "merchant-u5",
            "Ev. Marchant itinérant R5" to "merchant-r5",
            "Ev. Marchant itinérant B5" to "merchant-b5",
            "Ev. Marchant itinérant W3" to "merchant-w3",
            "Ev. Marchant itinérant U3" to "merchant-u3",
            "Ev. Marchant itinérant R3" to "merchant-r3",
            "Ev. Marchant itinérant G3" to "merchant-g3",
            "Ev. Marchant itinérant B3" to "merchant-b3",
            "Ev. Marchant itinérant G5" to "merchant-g5",
            "Ev. Marchant itinérant W5" to "merchant-w5",
        )
        for ((name, filename) in images) {
            OtariaEventArtwork.forName(name) shouldBe "/images/otaria/events/$filename.png"
        }
        OtariaEventArtwork.forName("Naturalize") shouldBe null
        OtariaEventArtwork.forName("Ev. Unknown") shouldBe null
    }

    test("event draw, copies and discard survive projection in resolution order for every seat") {
        val name = "Ev. Blocage cérébral"
        val original = EntityId("original")
        val copies = listOf(EntityId("copy-1"), EntityId("copy-2"))
        val events = listOf(
            OtariaExplorationStartedEvent(explorer, manager, source, OtariaPileCard(name, OtariaCardKind.EVENT)),
            OtariaEventCopiesCreatedEvent(explorer, original, name, copies),
            OtariaEventCopyFinishedEvent(explorer, copies[0]),
            OtariaEventCopyFinishedEvent(explorer, copies[1]),
            OtariaEventFinishedEvent(explorer, source, name),
        )
        for (viewer in listOf(explorer, manager, EntityId("observer"))) {
            val notices = transformer.transform(events, viewer).map { it as ClientEvent.OtariaNotice }
            notices.size shouldBe 5
            notices.map { it.explorerId } shouldBe List(5) { explorer }
            notices.first().cardName shouldBe name
            notices.first().imageUri shouldBe "/images/otaria/events/blocage-cerebral.png"
            notices[1].description shouldContain "2 event copies"
            notices[2].description shouldContain "copy finished"
            notices[3].description shouldContain "copy finished"
            notices.last().description shouldContain "discarded"
            notices.last().imageUri shouldBe notices.first().imageUri
            val payload = Json.encodeToString<ClientEvent>(notices.first())
            Json.decodeFromString<ClientEvent>(payload) shouldBe notices.first()
        }
    }

    test("unsupported events remain explicit and keep any supplied artwork") {
        val name = "Ev. Flux du jusant"
        val notice = transformer.transform(
            listOf(OtariaCardSkippedEvent(explorer, source, name)), explorer,
        ).single() as ClientEvent.OtariaNotice
        notice.description shouldContain "discarded without resolving"
        notice.imageUri shouldBe "/images/otaria/events/flux-du-jusant.png"
    }

    test("draw notices do not expose private creature identities") {
        val hidden = OtariaExplorationStartedEvent(
            explorer, manager, source, OtariaPileCard("Hidden morph", OtariaCardKind.CREATURE),
        )
        transformer.transform(listOf(hidden), explorer) shouldBe emptyList()
        transformer.transform(listOf(hidden), EntityId("spectator")) shouldBe emptyList()
    }
})
