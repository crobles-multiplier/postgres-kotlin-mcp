package model.security

/**
 * Data class representing parsed column sensitivity information for PII protection
 *
 * This class parses and interprets database column comments that contain JSON metadata
 * about data sensitivity and privacy classification.
 *
 * Expected comment format: [{"sensitivity":"internal", "privacy":"non-personal"}]
 *
 * @property sensitivity Sensitivity level of the data (e.g., "internal", "public", "restricted", "confidential")
 * @property privacy Privacy classification of the data ("personal" for PII, "non-personal" for safe data)
 */
data class ColumnSensitivityInfo(
    val sensitivity: String,  // "internal", "public", "restricted", etc.
    val privacy: String       // "personal", "non-personal"
) {
    /**
     * Whether this column contains personally identifiable information (PII)
     */
    val isPii: Boolean get() = privacy == "personal"
    
    /**
     * Whether this column has high sensitivity classification
     */
    val isHighSensitivity: Boolean get() = sensitivity in listOf("restricted", "confidential")
}
