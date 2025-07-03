package model.connection

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import model.Environment
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

class EnvironmentManagerTest {

    @BeforeEach
    fun setup() {
        mockkObject(ConfigurationLoader)
    }

    @AfterEach
    fun cleanup() {
        unmockkObject(ConfigurationLoader)
    }

    @Test
    fun `should get JDBC URL for environment`() {
        every { ConfigurationLoader.getProperty("database.staging.jdbc-url") } returns "jdbc:postgresql://localhost:5432/testdb"
        
        val result = EnvironmentManager.getJdbcUrl(Environment.STAGING)
        
        assertEquals("jdbc:postgresql://localhost:5432/testdb", result)
    }

    @Test
    fun `should get username for environment`() {
        every { ConfigurationLoader.getProperty("database.production.username") } returns "produser"
        
        val result = EnvironmentManager.getUsername(Environment.PRODUCTION)
        
        assertEquals("produser", result)
    }

    @Test
    fun `should get password for environment`() {
        every { ConfigurationLoader.getProperty("database.release.password") } returns "releasepass"
        
        val result = EnvironmentManager.getPassword(Environment.RELEASE)
        
        assertEquals("releasepass", result)
    }

    @Test
    fun `should return null for missing configuration`() {
        every { ConfigurationLoader.getProperty(any()) } returns null
        
        val jdbcUrl = EnvironmentManager.getJdbcUrl(Environment.STAGING)
        val username = EnvironmentManager.getUsername(Environment.STAGING)
        val password = EnvironmentManager.getPassword(Environment.STAGING)
        
        assertNull(jdbcUrl)
        assertNull(username)
        assertNull(password)
    }

    @Test
    fun `should check complete configuration correctly`() {
        // Mock complete configuration
        every { ConfigurationLoader.getProperty("database.staging.jdbc-url") } returns "jdbc:postgresql://localhost:5432/testdb"
        every { ConfigurationLoader.getProperty("database.staging.username") } returns "testuser"
        every { ConfigurationLoader.getProperty("database.staging.password") } returns "testpass"
        
        assertTrue(EnvironmentManager.hasCompleteConfiguration(Environment.STAGING))
    }

    @Test
    fun `should detect incomplete configuration`() {
        // Mock incomplete configuration (missing password)
        every { ConfigurationLoader.getProperty("database.staging.jdbc-url") } returns "jdbc:postgresql://localhost:5432/testdb"
        every { ConfigurationLoader.getProperty("database.staging.username") } returns "testuser"
        every { ConfigurationLoader.getProperty("database.staging.password") } returns null
        
        assertFalse(EnvironmentManager.hasCompleteConfiguration(Environment.STAGING))
    }

    @Test
    fun `should get available environments with complete configuration`() {
        // Mock staging as complete, production as incomplete
        every { ConfigurationLoader.getProperty("database.staging.jdbc-url") } returns "jdbc:postgresql://localhost:5432/testdb"
        every { ConfigurationLoader.getProperty("database.staging.username") } returns "testuser"
        every { ConfigurationLoader.getProperty("database.staging.password") } returns "testpass"
        
        every { ConfigurationLoader.getProperty("database.production.jdbc-url") } returns "jdbc:postgresql://prod:5432/proddb"
        every { ConfigurationLoader.getProperty("database.production.username") } returns "produser"
        every { ConfigurationLoader.getProperty("database.production.password") } returns null // Missing
        
        every { ConfigurationLoader.getProperty("database.release.jdbc-url") } returns null // Missing
        every { ConfigurationLoader.getProperty("database.release.username") } returns "releaseuser"
        every { ConfigurationLoader.getProperty("database.release.password") } returns "releasepass"
        
        val available = EnvironmentManager.getAvailableEnvironments()
        
        assertEquals(1, available.size)
        assertTrue(available.contains(Environment.STAGING))
        assertFalse(available.contains(Environment.PRODUCTION))
        assertFalse(available.contains(Environment.RELEASE))
    }
}
