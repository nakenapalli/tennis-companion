package com.tenniscompanion.security

import com.tenniscompanion.domain.User
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

@Service
class JwtService(private val encoder: JwtEncoder) {

    /** Issues a 12h token: subject = userId, plus email + roles claims. */
    fun issue(user: User): String {
        val roles = buildList {
            add("ROLE_USER")
            if (user.isAdmin) add("ROLE_ADMIN")
        }
        val now = Instant.now()
        val claims = JwtClaimsSet.builder()
            .subject(user.id.toString())
            .issuedAt(now)
            .expiresAt(now.plus(Duration.ofHours(12)))
            .claim("email", user.email)
            .claim("username", user.username ?: "")
            .claim("roles", roles)
            .build()
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        return encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
    }
}
