package com.wingedsheep.engine.otaria

/** Supplied Otaria proxies, not the ordinary Magic cards with similar names. */
object OtariaEventArtwork {
    fun forName(name: String): String? = when (name) {
        "Ev. Naturalisation" -> "/images/otaria/events/naturalisation.png"
        "Ev. Frappe Concertée" -> "/images/otaria/events/frappe-concertee.png"
        "Ev. Infestation" -> "/images/otaria/events/infestation.png"
        "Ev. Pyroclasme" -> "/images/otaria/events/pyroclasme.png"
        "Ev. Blocage cérébral" -> "/images/otaria/events/blocage-cerebral.png"
        "Ev. Flux du jusant" -> "/images/otaria/events/flux-du-jusant.png"
        "Ev. Marchant itinérant U5" -> "/images/otaria/events/merchant-u5.png"
        "Ev. Marchant itinérant R5" -> "/images/otaria/events/merchant-r5.png"
        "Ev. Marchant itinérant B5" -> "/images/otaria/events/merchant-b5.png"
        "Ev. Marchant itinérant W3" -> "/images/otaria/events/merchant-w3.png"
        "Ev. Marchant itinérant U3" -> "/images/otaria/events/merchant-u3.png"
        "Ev. Marchant itinérant R3" -> "/images/otaria/events/merchant-r3.png"
        "Ev. Marchant itinérant G3" -> "/images/otaria/events/merchant-g3.png"
        "Ev. Marchant itinérant B3" -> "/images/otaria/events/merchant-b3.png"
        "Ev. Marchant itinérant G5" -> "/images/otaria/events/merchant-g5.png"
        "Ev. Marchant itinérant W5" -> "/images/otaria/events/merchant-w5.png"
        else -> null
    }
}
