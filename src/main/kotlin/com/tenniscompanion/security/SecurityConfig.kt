package com.tenniscompanion.security

import com.nimbusds.jose.jwk.source.ImmutableSecret
import com.nimbusds.jose.proc.SecurityContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.Customizer
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter
import org.springframework.security.web.SecurityFilterChain
import javax.crypto.spec.SecretKeySpec

/**
 * Stateless JWT (HMAC-SHA256) security. Public reads stay open; the me-endpoints need a user token;
 * the admin-endpoints need ROLE_ADMIN. Tokens are issued by JwtService and validated by the resource
 * server filter using the same shared secret.
 */
@Configuration
class SecurityConfig(
    @Value("\${app.jwt.secret}") jwtSecret: String,
    @Value("\${app.cors.allowed-origins:http://localhost:3000}") private val allowedOrigins: List<String>,
) {

    // HS256 requires a >= 256-bit (32-byte) key.
    private val secretKey = SecretKeySpec(jwtSecret.toByteArray(Charsets.UTF_8), "HmacSHA256")

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val config = CorsConfiguration().apply {
            allowedOrigins = this@SecurityConfig.allowedOrigins
            allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS")
            allowedHeaders = listOf("*")
        }
        return UrlBasedCorsConfigurationSource().apply { registerCorsConfiguration("/**", config) }
    }

    @Bean
    fun jwtDecoder(): JwtDecoder =
        NimbusJwtDecoder.withSecretKey(secretKey).macAlgorithm(MacAlgorithm.HS256).build()

    @Bean
    fun jwtEncoder(): JwtEncoder = NimbusJwtEncoder(ImmutableSecret<SecurityContext>(secretKey))

    @Bean
    fun jwtAuthenticationConverter(): JwtAuthenticationConverter {
        // our tokens carry a "roles" claim already prefixed with ROLE_
        val authorities = JwtGrantedAuthoritiesConverter().apply {
            setAuthoritiesClaimName("roles")
            setAuthorityPrefix("")
        }
        return JwtAuthenticationConverter().apply { setJwtGrantedAuthoritiesConverter(authorities) }
    }

    @Bean
    fun filterChain(http: HttpSecurity, converter: JwtAuthenticationConverter): SecurityFilterChain {
        http
            .csrf { it.disable() } // stateless API, no cookies
            .cors(Customizer.withDefaults()) // uses the corsConfigurationSource bean below
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers("/api/auth/**", "/api/health", "/actuator/health").permitAll()
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/players/**", "/api/scores/**", "/api/rankings", "/api/tournaments/**", "/api/insights/**",
                    ).permitAll()
                    .requestMatchers("/api/admin/**").hasRole("ADMIN")
                    .requestMatchers("/api/me/**").authenticated()
                    .anyRequest().authenticated()
            }
            .oauth2ResourceServer { rs -> rs.jwt { it.jwtAuthenticationConverter(converter) } }
        return http.build()
    }
}
