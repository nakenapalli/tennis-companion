package com.tenniscompanion.config

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource
import java.io.File

/**
 * Loads a project-root `.env` into the Spring environment. Inserted just **above** `systemEnvironment`
 * so `.env` values reliably resolve (a bare/empty OS env var of the same name won't shadow them) while
 * command-line args + system properties still override — that's how the `--app.tennis-api.base-url`
 * test override keeps working. Replaces the spring-dotenv library, which registers via the old
 * `spring.factories` and is therefore not picked up by Spring Boot 4 (which uses an `.imports` file
 * — see META-INF/spring/...EnvironmentPostProcessor.imports).
 *
 * Parser is intentionally forgiving: skips blanks/`#` lines, strips ` #` inline comments and
 * surrounding quotes, trims whitespace.
 */
class DotenvEnvironmentPostProcessor : EnvironmentPostProcessor {

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        if (environment.propertySources.contains(SOURCE_NAME)) return
        val file = File(".env")
        if (!file.exists()) return

        val values = HashMap<String, Any>()
        file.forEachLine { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachLine
            val eq = line.indexOf('=')
            if (eq <= 0) return@forEachLine
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).split(" #", limit = 2)[0].trim().trim('"', '\'')
            if (value.isNotEmpty()) values[key] = value
        }
        if (values.isEmpty()) return
        val source = MapPropertySource(SOURCE_NAME, values)
        // Above systemEnvironment (so empty/absent OS vars don't shadow .env), below command-line args.
        if (environment.propertySources.contains("systemEnvironment")) {
            environment.propertySources.addBefore("systemEnvironment", source)
        } else {
            environment.propertySources.addFirst(source)
        }
    }

    companion object {
        private const val SOURCE_NAME = "dotenv"
    }
}
