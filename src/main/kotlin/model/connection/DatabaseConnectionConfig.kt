package model.connection

import model.Environment

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
    val environment: Environment
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

        // Environment is already validated by the enum type - no need for additional validation

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
    

    


    companion object {

        /**
         * Creates a DatabaseConnectionConfig from individual properties with validation
         *
         * @param jdbcUrl The JDBC URL for the database connection
         * @param username The database username
         * @param password The database password
         * @param environment The environment enum
         * @return A validated DatabaseConnectionConfig instance
         * @throws IllegalArgumentException if any required property is missing or invalid
         */
        fun create(
            jdbcUrl: String?,
            username: String?,
            password: String?,
            environment: Environment
        ): DatabaseConnectionConfig {

            val validationErrors = mutableListOf<String>()

            if (jdbcUrl.isNullOrBlank()) {
                validationErrors.add("JDBC URL is required for environment '${environment.value}'")
            }

            if (username.isNullOrBlank()) {
                validationErrors.add("Username is required for environment '${environment.value}'")
            }

            if (password.isNullOrBlank()) {
                validationErrors.add("Password is required for environment '${environment.value}'")
            }

            if (validationErrors.isNotEmpty()) {
                val envUpper = environment.value.uppercase()
                throw IllegalArgumentException("""
                    Missing required database connection properties for environment '${environment.value}':
                    ${validationErrors.joinToString("\n") { "  - $it" }}

                    Required database connection properties for environment '${environment.value}':

                    Properties in database.properties:
                    - database.${environment.value.lowercase()}.jdbc-url=${'$'}{POSTGRES_${envUpper}_JDBC_URL}
                    - database.${environment.value.lowercase()}.username=${'$'}{POSTGRES_${envUpper}_USERNAME}
                    - database.${environment.value.lowercase()}.password=${'$'}{POSTGRES_${envUpper}_PASSWORD}

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
         * @param environment The environment enum
         * @return DatabaseConnectionConfig for the environment
         * @throws IllegalArgumentException if the environment is not supported or not fully configured
         */
        fun forEnvironment(environment: Environment): DatabaseConnectionConfig {
            return EnvironmentManager.createConnectionConfig(environment)
        }


    }
}
