package com.wingedsheep.gameserver.controller

import com.wingedsheep.gameserver.otaria.OtariaPlaytestRequest
import com.wingedsheep.gameserver.otaria.OtariaPlaytestService
import com.wingedsheep.gameserver.otaria.OtariaLobbyCreateRequest
import com.wingedsheep.gameserver.otaria.OtariaLobbyJoinRequest
import com.wingedsheep.gameserver.otaria.OtariaLobbyMemberRequest
import com.wingedsheep.gameserver.otaria.OtariaLobbyReadyRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
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

    @PostMapping("/lobbies")
    fun createLobby(@RequestBody request: OtariaLobbyCreateRequest) = playtest.createLobby(request)

    @GetMapping("/lobbies/{lobbyId}")
    fun lobby(
        @PathVariable lobbyId: String,
        @RequestParam(required = false) memberToken: String?,
    ) = playtest.getLobby(lobbyId, memberToken)

    @PostMapping("/lobbies/{lobbyId}/join")
    fun joinLobby(@PathVariable lobbyId: String, @RequestBody request: OtariaLobbyJoinRequest) =
        playtest.joinLobby(lobbyId, request)

    @PutMapping("/lobbies/{lobbyId}/ready")
    fun setReady(@PathVariable lobbyId: String, @RequestBody request: OtariaLobbyReadyRequest) =
        playtest.setLobbyReady(lobbyId, request)

    @PostMapping("/lobbies/{lobbyId}/start")
    fun startLobby(@PathVariable lobbyId: String, @RequestBody request: OtariaLobbyMemberRequest) =
        playtest.startLobby(lobbyId, request.memberToken)

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalidRequest(error: IllegalArgumentException) = ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(mapOf("errors" to listOf(requireNotNull(error.message))))
}
