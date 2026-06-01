package com.tenniscompanion.api

import com.tenniscompanion.domain.User
import com.tenniscompanion.domain.UserRepository
import com.tenniscompanion.security.JwtService
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

data class RegisterRequest(val email: String, val password: String)
data class LoginRequest(val email: String, val password: String)
data class AuthResponse(val token: String, val email: String, val admin: Boolean)

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val users: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwt: JwtService,
) {
    @PostMapping("/register")
    fun register(@RequestBody req: RegisterRequest): AuthResponse {
        val email = req.email.trim().lowercase()
        if (email.isBlank() || req.password.length < 8) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "email required and password must be >= 8 chars")
        }
        if (users.existsByEmail(email)) throw ResponseStatusException(HttpStatus.CONFLICT, "email already registered")
        val user = users.save(
            User(email = email, passwordHash = passwordEncoder.encode(req.password)!!, createdAt = Instant.now()),
        )
        return AuthResponse(jwt.issue(user), user.email, user.isAdmin)
    }

    @PostMapping("/login")
    fun login(@RequestBody req: LoginRequest): AuthResponse {
        val user = users.findByEmail(req.email.trim().lowercase())
        if (user == null || !passwordEncoder.matches(req.password, user.passwordHash)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid credentials")
        }
        return AuthResponse(jwt.issue(user), user.email, user.isAdmin)
    }
}
