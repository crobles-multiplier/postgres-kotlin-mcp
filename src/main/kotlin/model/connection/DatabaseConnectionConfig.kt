package model.connection

/**
 * Data Transfer Object for database connection configuration
 *
 * Encapsulates database connection properties and provides validation logic.
 * Follows HikariCP standard configuration format.
 *
 * This class focuses solely on connection data and validation.
 * Configuration loading is handled by ConfigurationLoader.
 * Environment management is handled by EnvironmentManager.
 */
data class DatabaseConnectionConfig(
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val environment: String
) {

    init {
        validate()
    }

    /**
     * Validates the database connection configuration
     * @throws IllegalArgumentException if any required property is invalid
     */
    private fun validate() {
        require(jdbcUrl.isNotBlank()) {
            "JDBC URL cannot be blank for environment '$environment'"
        }

        require(jdbcUrl.startsWith("jdbc:postgresql://")) {
            "JDBC URL must start with 'jdbc:postgresql://' for environment '$environment'. Got: $jdbcUrl"
        }

        require(username.isNotBlank()) {
            "Username cannot be blank for environment '$environment'"
        }

        require(password.isNotBlank()) {
            "Password cannot be blank for environment '$environment'"
        }

        require(environment.isNotBlank()) {
            "Environment cannot be blank"
        }

        // Validate environment is supported
        EnvironmentManager.validateEnvironment(environment)

        // Validate JDBC URL format
        validateJdbcUrl()
    }
    
    /**
     * Validates the JDBC URL format for PostgreSQL
     */
    private fun validateJdbcUrl() {
        val jdbcPattern = Regex("^jdbc:postgresql://([^:]+):(\\d+)/([^?]+)(\\?.*)?$")
        
        if (!jdbcPattern.matches(jdbcUrl)) {
            throw IllegalArgumentException("""
                Invalid JDBC URL format for environment '$environment'.
                Expected format: jdbc:postgresql://host:port/database[?parameters]
                Got: $jdbcUrl
                
                Examples:
                - jdbc:postgresql://localhost:5432/mydb
                - jdbc:postgresql://db.example.com:5432/production_db?ssl=true
            """.trimIndent())
        }
    }
    
    /**
     * Extracts the host from the JDBC URL
     */
    fun getHost(): String {
        val regex = Regex("jdbc:postgresql://([^:]+):")
        return regex.find(jdbcUrl)?.groupValues?.get(1) 
            ?: throw IllegalStateException("Could not extract host from JDBC URL: $jdbcUrl")
    }
    
    /**
     * Extracts the port from the JDBC URL
     */
    fun getPort(): Int {
        val regex = Regex("jdbc:postgresql://[^:]+:(\\d+)/")
        val portString = regex.find(jdbcUrl)?.groupValues?.get(1)
            ?: throw IllegalStateException("Could not extract port from JDBC URL: $jdbcUrl")
        
        return portString.toIntOrNull() 
            ?: throw IllegalStateException("Invalid port number in JDBC URL: $jdbcUrl")
    }
    
    /**
     * Extracts the database name from the JDBC URL
     */
    fun getDatabaseName(): String {
        val regex = Regex("jdbc:postgresql://[^:]+:\\d+/([^?]+)")
        return regex.find(jdbcUrl)?.groupValues?.get(1)
            ?: throw IllegalStateException("Could not extract database name from JDBC URL: $jdbcUrl")
    }
    
    /**
     * Gets connection parameters from the JDBC URL (if any)
     */
    fun getConnectionParameters(): Map<String, String> {
        val regex = Regex("jdbc:postgresql://[^?]+\\?(.+)")
        val paramString = regex.find(jdbcUrl)?.groupValues?.get(1) ?: return emptyMap()
        
        return paramString.split("&")
            .mapNotNull { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }
            .toMap()
    }
    
    /**
     * Creates a masked version for logging (hides password)
     */
    fun toMaskedString(): String {
        return "DatabaseConnectionConfig(jdbcUrl='$jdbcUrl', username='$username', password='***', environment='$environment')"
    }

    companion object {

        /**
         * Creates a DatabaseConnectionConfig from individual properties with validation
         *
         * @param jdbcUrl The JDBC URL for the database connection
         * @param username The database username
         * @param password The database password
         * @param environment The environment name
         * @return A validated DatabaseConnectionConfig instance
         * @throws IllegalArgumentException if any required property is missing or invalid
         */
        fun create(
            jdbcUrl: String?,
            username: String?,
            password: String?,
            environment: String
        ): DatabaseConnectionConfig {

            val validationErrors = mutableListOf<String>()

            if (jdbcUrl.isNullOrBlank()) {
                validationErrors.add("JDBC URL is required for environment '$environment'")
            }

            if (username.isNullOrBlank()) {
                validationErrors.add("Username is required for environment '$environment'")
            }

            if (password.isNullOrBlank()) {
                validationErrors.add("Password is required for environment '$environment'")
            }

            if (validationErrors.isNotEmpty()) {
                val envUpper = environment.uppercase()
                throw IllegalArgumentException("""
                    Missing required database connection properties for environment '$environment':
                    ${validationErrors.joinToString("\n") { "  - $it" }}

                    Required database connection properties for environment '$environment':

                    Properties in database.properties:
                    - database.${environment.lowercase()}.jdbc-url=${'$'}{POSTGRES_${envUpper}_JDBC_URL}
                    - database.${environment.lowercase()}.username=${'$'}{POSTGRES_${envUpper}_USERNAME}
                    - database.${environment.lowercase()}.password=${'$'}{POSTGRES_${envUpper}_PASSWORD}

                    Environment variables to set:
                    - export POSTGRES_${envUpper}_JDBC_URL="jdbc:postgresql://host:port/database"
                    - export POSTGRES_${envUpper}_USERNAME="your_username"
                    - export POSTGRES_${envUpper}_PASSWORD="your_password"
                """.trimIndent())
            }

            return DatabaseConnectionConfig(
                jdbcUrl = jdbcUrl!!,
                username = username!!,
                password = password!!,
                environment = environment
            )
        }

        /**
         * Creates a DatabaseConnectionConfig for the specified environment
         *
         * Uses EnvironmentManager to retrieve environment-specific configuration.
         *
         * @param environment The environment name
         * @return DatabaseConnectionConfig for the environment
         * @throws IllegalArgumentException if the environment is not supported or not fully configured
         */
        fun forEnvironment(environment: String): DatabaseConnectionConfig {
            return EnvironmentManager.createConnectionConfig(environment)
        }

        /**
         * Check if environment has complete database configuration
         *
         * @param environment The environment name to check
         * @return true if all required properties are configured, false otherwise
         */
        fun hasCompleteConfiguration(environment: String): Boolean {
            return EnvironmentManager.hasCompleteConfiguration(environment)
        }

        /**
         * Get all available environments that have complete configuration
         *
         * @return List of environment names with complete configuration
         */
        fun getAvailableEnvironments(): List<String> {
            return EnvironmentManager.getAvailableEnvironments()
        }

        /**
         * Get database configuration for all available environments
         *
         * @return Map of environment names to their DatabaseConnectionConfig
         */
        fun getAllConfigs(): Map<String, DatabaseConnectionConfig> {
            return EnvironmentManager.getAllConfigurations()
        }
    }
}
