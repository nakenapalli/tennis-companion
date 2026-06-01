package com.tenniscompanion

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling // pollers and the weekly digest run via @Scheduled (design §6.2, §6.5)
@ConfigurationPropertiesScan // binds @ConfigurationProperties classes (e.g. HistoricalLoadProperties)
class TennisCompanionApplication

// `fun main` at file top-level is idiomatic Kotlin — no wrapper class needed. The spread
// operator `*args` forwards the array as varargs into runApplication.
fun main(args: Array<String>) {
	runApplication<TennisCompanionApplication>(*args)
}
