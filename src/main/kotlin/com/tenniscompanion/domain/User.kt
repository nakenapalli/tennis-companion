package com.tenniscompanion.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

@Entity
@Table(name = "users")
class User(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    val email: String,
    val username: String? = null, // chat handle; unique (case-insensitive), required at registration
    val passwordHash: String,
    // explicit @Column avoids the Kotlin `is`-prefixed-boolean property/column mapping quirk
    @Column(name = "is_admin")
    val isAdmin: Boolean = false,
    val createdAt: Instant? = null,
)

interface UserRepository : JpaRepository<User, Long> {
    fun findByEmail(email: String): User?
    fun existsByEmail(email: String): Boolean
    fun existsByUsernameIgnoreCase(username: String): Boolean
}
