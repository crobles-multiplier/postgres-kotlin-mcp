import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows

class DatabaseConnectionConfigIntegrationTest {

    @Test
    fun `should get database config for staging environment`() {
        val config = DatabaseConnectionConfig.forEnvironment("staging")
        assertTrue(config.jdbcUrl.startsWith("jdbc:postgresql://"))
        assertEquals("testuser", config.username)
        assertEquals("testpass", config.password)
        assertEquals("staging", config.environment)
    }

    @Test
    fun `should have complete configuration for staging`() {
        assertTrue(DatabaseConnectionConfig.hasCompleteConfiguration("staging"))
    }

    @Test
    fun `should have complete configuration for release`() {
        assertTrue(DatabaseConnectionConfig.hasCompleteConfiguration("release"))
    }

    @Test
    fun `should return false for unknown environment`() {
        assertFalse(DatabaseConnectionConfig.hasCompleteConfiguration("unknown"))

        assertThrows<IllegalArgumentException> {
            DatabaseConnectionConfig.forEnvironment("unknown")
        }
    }

    @Test
    fun `should handle case insensitive environment names`() {
        val stagingConfig = DatabaseConnectionConfig.forEnvironment("STAGING")
        assertEquals("staging", stagingConfig.environment.lowercase())

        val releaseConfig = DatabaseConnectionConfig.forEnvironment("Release")
        assertEquals("release", releaseConfig.environment.lowercase())
    }

    @Test
    fun `should get available environments`() {
        val environments = DatabaseConnectionConfig.getAvailableEnvironments()
        assertTrue(environments.contains("staging"))
        assertTrue(environments.contains("release"))
        assertFalse(environments.contains("nonexistent"))
    }

    @Test
    fun `should get all database configs`() {
        val configs = DatabaseConnectionConfig.getAllConfigs()
        assertTrue(configs.containsKey("staging"))
        assertTrue(configs.containsKey("release"))

        val stagingConfig = configs["staging"]!!
        assertEquals("staging", stagingConfig.environment)
        assertEquals("testuser", stagingConfig.username)
    }

    @Test
    fun `should get properties from database properties file`() {
        val stagingMaxPoolSize = DatabaseConnectionConfig.getProperty("hikari.staging.maximum-pool-size")
        assertEquals("5", stagingMaxPoolSize)

        val releaseMaxPoolSize = DatabaseConnectionConfig.getProperty("hikari.release.maximum-pool-size")
        assertEquals("8", releaseMaxPoolSize)
    }
}
