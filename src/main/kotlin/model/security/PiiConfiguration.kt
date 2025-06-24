package model.security

import model.connection.ConfigurationLoader
import model.connection.EnvironmentManager

/**
 * Configuration manager for PII (Personally Identifiable Information) handling
 * 
 * Manages PII-related configuration settings, particularly for production environments
 * where sensitive data protection is critical.
 */
object PiiConfiguration {
    
    /**
     * Configuration key for PII checking in production
     */
    private const val PII_CHECKING_PRODUCTION_KEY = "pii.checking.production.enabled"
    
    /**
     * Checks if PII protection should be applied for a given environment
     *
     * PII protection is only applied in production environments when enabled.
     *
     * @param environment The environment name to check
     * @return true if PII protection should be applied, false otherwise
     * @throws IllegalStateException if the configuration is missing (fail-fast approach)
     * @throws IllegalArgumentException if the configuration value is invalid
     */
    fun shouldApplyPiiProtection(environment: String): Boolean {
        if (!EnvironmentManager.isProduction(environment)) {
            return false
        }

        val value = ConfigurationLoader.getProperty(PII_CHECKING_PRODUCTION_KEY)?.lowercase()

        return when (value) {
            "true" -> true
            "false" -> false
            null -> throw IllegalStateException("""
                PII checking configuration is required for production environment.
                Please add the following property to database.properties:

                $PII_CHECKING_PRODUCTION_KEY=true   # Enable PII protection (recommended)
                # OR
                $PII_CHECKING_PRODUCTION_KEY=false  # Disable PII protection (not recommended)

                This setting controls whether sensitive data is automatically filtered
                from production database queries to prevent accidental exposure of PII.
            """.trimIndent())
            else -> throw IllegalArgumentException("""
                Invalid value for $PII_CHECKING_PRODUCTION_KEY: '$value'
                Valid values are: true, false
            """.trimIndent())
        }
    }
}
