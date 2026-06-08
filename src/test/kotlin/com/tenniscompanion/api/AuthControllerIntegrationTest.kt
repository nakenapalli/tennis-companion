package com.tenniscompanion.api

import com.tenniscompanion.TestcontainersConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.web.server.ResponseStatusException

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@TestPropertySource(properties = ["app.poll.enabled=false"])
class AuthControllerIntegrationTest(@Autowired val auth: AuthController) {

    @Test
    fun `register issues a token and returns the username`() {
        val res = auth.register(RegisterRequest("newbie@example.com", "Newbie_1", "password123"))
        assertEquals("Newbie_1", res.username)
        assertTrue(res.token.isNotBlank())
    }

    @Test
    fun `username uniqueness is case-insensitive`() {
        auth.register(RegisterRequest("a@example.com", "Champion", "password123"))
        val ex = assertThrows(ResponseStatusException::class.java) {
            auth.register(RegisterRequest("b@example.com", "champion", "password123"))
        }
        assertEquals(409, ex.statusCode.value())
    }

    @Test
    fun `rejects an invalid username`() {
        val ex = assertThrows(ResponseStatusException::class.java) {
            auth.register(RegisterRequest("c@example.com", "no", "password123")) // too short
        }
        assertEquals(400, ex.statusCode.value())
    }
}
