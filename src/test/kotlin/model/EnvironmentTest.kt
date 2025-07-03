package model

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows

class EnvironmentTest {

    @Test
    fun `should have correct enum values`() {
        assertEquals("staging", Environment.STAGING.value)
        assertEquals("release", Environment.RELEASE.value)
        assertEquals("production", Environment.PRODUCTION.value)
    }

    @Test
    fun `should return staging as default environment`() {
        assertEquals(Environment.STAGING, Environment.getDefault())
    }

    @Test
    fun `should return all supported environments`() {
        val supported = Environment.getSupportedEnvironments()

        assertEquals(3, supported.size)
        assertTrue(supported.contains("staging"))
        assertTrue(supported.contains("release"))
        assertTrue(supported.contains("production"))
    }

    @Test
    fun `should return all environments`() {
        val all = Environment.getAllEnvironments()
        
        assertEquals(3, all.size)
        assertTrue(all.contains(Environment.STAGING))
        assertTrue(all.contains(Environment.RELEASE))
        assertTrue(all.contains(Environment.PRODUCTION))
    }

    @Test
    fun `should convert string to environment case insensitive`() {
        assertEquals(Environment.STAGING, Environment.fromString("staging"))
        assertEquals(Environment.STAGING, Environment.fromString("STAGING"))
        assertEquals(Environment.STAGING, Environment.fromString("Staging"))
        
        assertEquals(Environment.RELEASE, Environment.fromString("release"))
        assertEquals(Environment.RELEASE, Environment.fromString("RELEASE"))
        assertEquals(Environment.RELEASE, Environment.fromString("Release"))
        
        assertEquals(Environment.PRODUCTION, Environment.fromString("production"))
        assertEquals(Environment.PRODUCTION, Environment.fromString("PRODUCTION"))
        assertEquals(Environment.PRODUCTION, Environment.fromString("Production"))
    }

    @Test
    fun `should throw exception for invalid environment string`() {
        assertThrows<IllegalArgumentException> {
            Environment.fromString("invalid")
        }
        
        assertThrows<IllegalArgumentException> {
            Environment.fromString("test")
        }
        
        assertThrows<IllegalArgumentException> {
            Environment.fromString("")
        }
    }

    @Test
    fun `should handle empty input gracefully`() {
        assertThrows<IllegalArgumentException> {
            Environment.fromString("")
        }
    }
}
