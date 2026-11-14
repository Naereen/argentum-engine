package com.wingedsheep.gameserver.controller

import com.wingedsheep.gameserver.otaria.OtariaPlaytestRequest
import com.wingedsheep.gameserver.otaria.OtariaPlaytestService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/dev/otaria")
@ConditionalOnProperty(name = ["game.dev-endpoints.enabled"], havingValue = "true")
class OtariaPlaytestController(private val playtest: OtariaPlaytestService) {
    @GetMapping
    fun options() = playtest.options()

    @PostMapping
    fun start(@RequestBody request: OtariaPlaytestRequest) = playtest.start(request)

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalidRequest(error: IllegalArgumentException) = ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(mapOf("errors" to listOf(requireNotNull(error.message))))
}
