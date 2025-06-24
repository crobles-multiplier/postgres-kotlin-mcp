package model.connection

/**
 * Manages environment-specific database configurations
 * 
 * Provides utilities for working with different environments (staging, release, production)
 * and retrieving environment-specific database connection properties.
 */
object EnvironmentManager {
    
    /**
     * Supported database environments
     */
    enum class Environment(val value: String) {
        STAGING("staging"),
        RELEASE("release"),
        PRODUCTION("production");
        
        companion object {
            /**
             * Gets an Environment from a string value (case-insensitive)
             * 
             * @param value The environment string
             * @return The corresponding Environment enum
             * @throws IllegalArgumentException if the environment is not supported
             */
            fun fromString(value: String): Environment {
                return values().find { it.value.equals(value, ignoreCase = true) }
                    ?: throw IllegalArgumentException(
                        "Unsupported environment: '$value'. Supported environments: ${getSupportedEnvironments()}"
                    )
            }
            
            /**
             * Gets all supported environment names
             */
            fun getSupportedEnvironments(): List<String> {
                return values().map { it.value }
            }
        }
    }
    
    /**
     * Gets the JDBC URL property key for an environment
     */
    private fun getJdbcUrlKey(environment: String): String {
        return "database.${environment.lowercase()}.jdbc-url"
    }
    
    /**
     * Gets the username property key for an environment
     */
    private fun getUsernameKey(environment: String): String {
        return "database.${environment.lowercase()}.username"
    }
    
    /**
     * Gets the password property key for an environment
     */
    private fun getPasswordKey(environment: String): String {
        return "database.${environment.lowercase()}.password"
    }
    
    /**
     * Gets the JDBC URL for a specific environment
     * 
     * @param environment The environment name
     * @return The JDBC URL or null if not configured
     */
    fun getJdbcUrl(environment: String): String? {
        validateEnvironment(environment)
        return ConfigurationLoader.getProperty(getJdbcUrlKey(environment))
    }
    
    /**
     * Gets the username for a specific environment
     * 
     * @param environment The environment name
     * @return The username or null if not configured
     */
    fun getUsername(environment: String): String? {
        validateEnvironment(environment)
        return ConfigurationLoader.getProperty(getUsernameKey(environment))
    }
    
    /**
     * Gets the password for a specific environment
     * 
     * @param environment The environment name
     * @return The password or null if not configured
     */
    fun getPassword(environment: String): String? {
        validateEnvironment(environment)
        return ConfigurationLoader.getProperty(getPasswordKey(environment))
    }
    
    /**
     * Validates that the environment is supported
     * 
     * @param environment The environment name to validate
     * @throws IllegalArgumentException if the environment is not supported
     */
    fun validateEnvironment(environment: String) {
        Environment.fromString(environment) // This will throw if invalid
    }
    
    /**
     * Checks if an environment has complete database configuration
     * 
     * @param environment The environment name to check
     * @return true if all required properties are configured, false otherwise
     */
    fun hasCompleteConfiguration(environment: String): Boolean {
        return try {
            validateEnvironment(environment)
            val jdbcUrl = getJdbcUrl(environment)
            val username = getUsername(environment)
            val password = getPassword(environment)
            
            !jdbcUrl.isNullOrBlank() && !username.isNullOrBlank() && !password.isNullOrBlank()
        } catch (e: IllegalArgumentException) {
            false
        }
    }
    
    /**
     * Gets all environments that have complete database configuration
     * 
     * @return List of environment names with complete configuration
     */
    fun getAvailableEnvironments(): List<String> {
        return Environment.getSupportedEnvironments()
            .filter { hasCompleteConfiguration(it) }
    }
    
    /**
     * Gets all configured environments with their connection details
     * 
     * @return Map of environment names to their DatabaseConnectionConfig
     */
    fun getAllConfigurations(): Map<String, DatabaseConnectionConfig> {
        return getAvailableEnvironments().associateWith { environment ->
            createConnectionConfig(environment)
        }
    }
    
    /**
     * Creates a DatabaseConnectionConfig for the specified environment
     * 
     * @param environment The environment name
     * @return DatabaseConnectionConfig for the environment
     * @throws IllegalArgumentException if the environment is not supported or not fully configured
     */
    fun createConnectionConfig(environment: String): DatabaseConnectionConfig {
        validateEnvironment(environment)
        
        val jdbcUrl = getJdbcUrl(environment)
        val username = getUsername(environment)
        val password = getPassword(environment)
        
        return DatabaseConnectionConfig.create(
            jdbcUrl = jdbcUrl,
            username = username,
            password = password,
            environment = environment
        )
    }

    /**
     * Checks if an environment is production
     *
     * @param environment The environment name
     * @return true if the environment is production, false otherwise
     */
    fun isProduction(environment: String): Boolean {
        return environment.equals(Environment.PRODUCTION.value, ignoreCase = true)
    }
}
