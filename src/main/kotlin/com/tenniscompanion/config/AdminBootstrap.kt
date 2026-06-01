package com.tenniscompanion.config

import com.tenniscompanion.domain.User
import com.tenniscompanion.domain.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import java.time.Instant

@ConfigurationProperties(prefix = "app.admin")
data class AdminProperties(val email: String = "", val password: String = "")

/**
 * Ensures an admin user exists from config, since the admin endpoints now require ROLE_ADMIN. Skipped
 * unless both app.admin.email + app.admin.password are set (e.g. via .env). Dev convenience.
 */
@Component
class AdminBootstrap(
    private val props: AdminProperties,
    private val users: UserRepository,
    private val encoder: PasswordEncoder,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val email = props.email.trim().lowercase()
        if (email.isBlank() || props.password.isBlank()) return
        if (users.existsByEmail(email)) return
        users.save(User(email = email, passwordHash = encoder.encode(props.password)!!, isAdmin = true, createdAt = Instant.now()))
        log.info("Bootstrapped admin user: {}", email)
    }
}
