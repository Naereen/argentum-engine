package com.wingedsheep.gameserver.otaria.simulation

import com.wingedsheep.gameserver.config.GameBeansConfig
import com.wingedsheep.gameserver.config.GameProperties
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

fun main(args: Array<String>) {
    if (args.contentEquals(arrayOf("--help"))) {
        println("just otaria-simulate [--config FILE] [--matches N] [--players 3..5] [--seed N] " +
            "[--max-turns N] [--max-actions N] [--output DIR] [--quiet]")
        return
    }
    val quiet = "--quiet" in args
    require(args.count { it == "--quiet" } <= 1) { "Duplicate --quiet option" }
    val valuedArgs = args.filterNot { it == "--quiet" }
    require(valuedArgs.size % 2 == 0) { "Every option except --quiet needs a value; use --help" }
    val options = valuedArgs.chunked(2).associate { it[0] to it[1] }
    val allowed = setOf("--config", "--matches", "--players", "--seed", "--max-turns", "--max-actions", "--output")
    require(options.keys.all { it in allowed }) { "Unknown options: ${options.keys - allowed}" }
    require(options.size * 2 == valuedArgs.size) { "Duplicate options are not allowed" }
    val base = options["--config"]?.let {
        Json.decodeFromString<OtariaSimulationConfig>(Files.readString(Path.of(it)))
    } ?: OtariaSimulationConfig()
    val config = base.copy(
        matches = options["--matches"]?.toInt() ?: base.matches,
        players = options["--players"]?.toInt() ?: base.players,
        seed = options["--seed"]?.toLong() ?: base.seed,
        maxTurns = options["--max-turns"]?.toInt() ?: base.maxTurns,
        maxActions = options["--max-actions"]?.toInt() ?: base.maxActions,
        output = options["--output"] ?: base.output,
        verbose = if (quiet) false else base.verbose,
    )
    config.validate()
    val beans = GameBeansConfig(GameProperties())
    val cards = beans.cardRegistry()
    println("Otaria JSONL: ${OtariaBatchRunner(cards, beans.printingRegistry(cards)).run(config).toAbsolutePath()}")
}
