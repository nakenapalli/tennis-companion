package com.tenniscompanion

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@TestPropertySource(properties = ["app.poll.enabled=false"]) // don't hit the live feed during tests
class TennisCompanionApplicationTests {

	@Test
	fun contextLoads() {
	}

}
