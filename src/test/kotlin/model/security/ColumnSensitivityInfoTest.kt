package model.security

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ColumnSensitivityInfoTest {

    @Test
    fun `should identify non-PII column correctly`() {
        val nonPiiColumn = ColumnSensitivityInfo("internal", "non-personal")
        
        assertFalse(nonPiiColumn.isPii)
        assertEquals("internal", nonPiiColumn.sensitivity)
        assertEquals("non-personal", nonPiiColumn.privacy)
    }

    @Test
    fun `should identify PII column correctly`() {
        val piiColumn = ColumnSensitivityInfo("internal", "personal")
        
        assertTrue(piiColumn.isPii)
        assertEquals("internal", piiColumn.sensitivity)
        assertEquals("personal", piiColumn.privacy)
    }

    @Test
    fun `should identify high sensitivity correctly`() {
        val regularSensitivity = ColumnSensitivityInfo("internal", "personal")
        val highSensitivity = ColumnSensitivityInfo("confidential", "personal")
        
        assertFalse(regularSensitivity.isHighSensitivity)
        assertTrue(highSensitivity.isHighSensitivity)
    }

    @Test
    fun `should handle different privacy values`() {
        val personalColumn = ColumnSensitivityInfo("internal", "personal")
        val nonPersonalColumn = ColumnSensitivityInfo("internal", "non-personal")
        
        assertTrue(personalColumn.isPii)
        assertFalse(nonPersonalColumn.isPii)
    }
}
