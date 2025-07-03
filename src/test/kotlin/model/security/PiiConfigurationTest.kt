package model.security

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows

class PiiConfigurationTest {

    @Test
    fun `should throw exception for production without configuration`() {
        // Production environment requires configuration, should throw exception
        assertThrows<IllegalStateException> {
            PiiConfiguration.shouldApplyPiiProtection("production")
        }
    }

    @Test
    fun `should disable PII protection for non-production environments`() {
        // Non-production environments should always return false regardless of config
        assertFalse(PiiConfiguration.shouldApplyPiiProtection("staging"))
        assertFalse(PiiConfiguration.shouldApplyPiiProtection("release"))
        assertFalse(PiiConfiguration.shouldApplyPiiProtection("STAGING"))
        assertFalse(PiiConfiguration.shouldApplyPiiProtection("RELEASE"))
    }

    @Test
    fun `should throw exception for invalid environments`() {
        // Invalid environments should throw IllegalArgumentException from Environment.fromString
        assertThrows<IllegalArgumentException> {
            PiiConfiguration.shouldApplyPiiProtection("invalid")
        }

        assertThrows<IllegalArgumentException> {
            PiiConfiguration.shouldApplyPiiProtection("test")
        }
    }
}
