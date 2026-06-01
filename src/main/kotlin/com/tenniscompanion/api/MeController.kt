package com.tenniscompanion.api

import com.tenniscompanion.domain.FavoriteDto
import com.tenniscompanion.domain.UserPrefsStore
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class AddFavoriteRequest(val playerId: Long)

/** The authenticated user's personalization. `uid` comes from the JWT subject. */
@RestController
@RequestMapping("/api/me")
class MeController(private val store: UserPrefsStore) {

    @GetMapping("/home-config")
    fun getHomeConfig(@AuthenticationPrincipal jwt: Jwt): Map<String, Any?> = store.homeConfig(uid(jwt))

    @PutMapping("/home-config")
    fun putHomeConfig(@AuthenticationPrincipal jwt: Jwt, @RequestBody layout: Map<String, Any?>): Map<String, Any?> {
        store.saveHomeConfig(uid(jwt), layout)
        return layout
    }

    @GetMapping("/favorites")
    fun favorites(@AuthenticationPrincipal jwt: Jwt): List<FavoriteDto> = store.favorites(uid(jwt))

    @PostMapping("/favorites")
    fun addFavorite(@AuthenticationPrincipal jwt: Jwt, @RequestBody req: AddFavoriteRequest): Map<String, Any> {
        store.addFavorite(uid(jwt), req.playerId)
        return mapOf("added" to req.playerId)
    }

    @DeleteMapping("/favorites/{playerId}")
    fun removeFavorite(@AuthenticationPrincipal jwt: Jwt, @PathVariable playerId: Long): Map<String, Any> {
        store.removeFavorite(uid(jwt), playerId)
        return mapOf("removed" to playerId)
    }

    private fun uid(jwt: Jwt): Long = jwt.subject.toLong()
}
