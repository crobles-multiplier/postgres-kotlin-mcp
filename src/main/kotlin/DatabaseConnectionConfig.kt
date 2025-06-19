/**
 * Data Transfer Object for database connection configuration
 * 
 * Encapsulates database connection properties and provides validation logic.
 * Follows HikariCP standard configuration format.
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

        private fun loadConfig(configFile: String = "database.properties"): java.util.Properties {
            return java.util.Properties().apply {
                DatabaseConnectionConfig::class.java.getResourceAsStream("/$configFile")?.use { load(it) }
                    ?: throw IllegalStateException("Configuration file /$configFile not found in classpath")
            }
        }

        private val config = loadConfig()

        /**
         * Resolves environment variables in property values
         */
        private fun String.resolveEnvVar(): String? =
            if (startsWith("\${") && endsWith("}")) {
                System.getenv(substring(2, length - 1))
            } else this

        /**
         * Get property value with environment variable resolution
         */
        fun getProperty(key: String): String? = config.getProperty(key)?.resolveEnvVar()

        /**
         * Get database connection properties for an environment
         */
        private fun getJdbcUrl(environment: String): String? = when (environment.lowercase()) {
            "staging" -> getProperty("database.staging.jdbc-url")
            "release" -> getProperty("database.release.jdbc-url")
            "production" -> getProperty("database.production.jdbc-url")
            else -> null
        }

        private fun getUsername(environment: String): String? = when (environment.lowercase()) {
            "staging" -> getProperty("database.staging.username")
            "release" -> getProperty("database.release.username")
            "production" -> getProperty("database.production.username")
            else -> null
        }

        private fun getPassword(environment: String): String? = when (environment.lowercase()) {
            "staging" -> getProperty("database.staging.password")
            "release" -> getProperty("database.release.password")
            "production" -> getProperty("database.production.password")
            else -> null
        }

        /**
         * Creates a DatabaseConnectionConfig for the specified environment
         */
        fun forEnvironment(environment: String): DatabaseConnectionConfig {
            return create(
                jdbcUrl = getJdbcUrl(environment),
                username = getUsername(environment),
                password = getPassword(environment),
                environment = environment
            )
        }

        /**
         * Check if environment has complete database configuration
         */
        fun hasCompleteConfiguration(environment: String): Boolean {
            return try {
                forEnvironment(environment)
                true
            } catch (e: IllegalArgumentException) {
                false
            }
        }

        /**
         * Get all available environments that have complete configuration
         */
        fun getAvailableEnvironments(): List<String> {
            return listOf("staging", "release", "production")
                .filter { hasCompleteConfiguration(it) }
        }

        /**
         * Get database configuration for all available environments
         */
        fun getAllConfigs(): Map<String, DatabaseConnectionConfig> {
            return getAvailableEnvironments().associateWith { forEnvironment(it) }
        }

        /**
         * Creates a DatabaseConnectionConfig from individual properties with validation
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
                throw IllegalArgumentException("""
                    Missing required database connection properties for environment '$environment':
                    ${validationErrors.joinToString("\n") { "  - $it" }}

                    Please ensure the following properties are configured in database.properties:
                    - database.$environment.jdbc-url=${'$'}{POSTGRES_${environment.uppercase()}_JDBC_URL}
                    - database.$environment.username=${'$'}{POSTGRES_${environment.uppercase()}_USERNAME}
                    - database.$environment.password=${'$'}{POSTGRES_${environment.uppercase()}_PASSWORD}

                    And set the corresponding environment variables:
                    - export POSTGRES_${environment.uppercase()}_JDBC_URL="jdbc:postgresql://host:port/database"
                    - export POSTGRES_${environment.uppercase()}_USERNAME="your_username"
                    - export POSTGRES_${environment.uppercase()}_PASSWORD="your_password"
                """.trimIndent())
            }

            return DatabaseConnectionConfig(
                jdbcUrl = jdbcUrl!!,
                username = username!!,
                password = password!!,
                environment = environment
            )
        }
        

    }
}
