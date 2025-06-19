import java.util.*

/**
 * Simple database configuration for staging, release, and production environments
 */
object DatabaseConfiguration {

    private val config = Properties().apply {
        DatabaseConfiguration::class.java.getResourceAsStream("/database.properties")?.use { load(it) }
    }

    fun getConnectionString(environment: String): String? = when (environment.lowercase()) {
        "staging" -> config.getProperty("database.staging.url")?.resolveEnvVar()
        "release" -> config.getProperty("database.release.url")?.resolveEnvVar()
        "production" -> config.getProperty("database.production.url")?.resolveEnvVar()
        else -> null
    }

    fun getDefaultEnvironment(): String = "staging"

    /**
     * Get any property from database.properties with environment variable resolution
     */
    fun getProperty(key: String): String? = config.getProperty(key)?.resolveEnvVar()

    private fun String.resolveEnvVar(): String? =
        if (startsWith("\${") && endsWith("}")) {
            System.getenv(substring(2, length - 1))
        } else this
}
