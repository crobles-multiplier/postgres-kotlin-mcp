package model.connection

import model.Environment

/**
 * Manages environment-specific database configurations
 *
 * Provides utilities for working with different environments (staging, release, production)
 * and retrieving environment-specific database connection properties.
 */
object EnvironmentManager {

    /**
     * Gets the JDBC URL property key for an environment
     */
    private fun getJdbcUrlKey(environment: Environment): String {
        return "database.${environment.value.lowercase()}.jdbc-url"
    }

    /**
     * Gets the username property key for an environment
     */
    private fun getUsernameKey(environment: Environment): String {
        return "database.${environment.value.lowercase()}.username"
    }

    /**
     * Gets the password property key for an environment
     */
    private fun getPasswordKey(environment: Environment): String {
        return "database.${environment.value.lowercase()}.password"
    }

    /**
     * Gets the JDBC URL for a specific environment
     *
     * @param environment The environment enum
     * @return The JDBC URL or null if not configured
     */
    fun getJdbcUrl(environment: Environment): String? {
        return ConfigurationLoader.getProperty(getJdbcUrlKey(environment))
    }

    /**
     * Gets the username for a specific environment
     *
     * @param environment The environment enum
     * @return The username or null if not configured
     */
    fun getUsername(environment: Environment): String? {
        return ConfigurationLoader.getProperty(getUsernameKey(environment))
    }

    /**
     * Gets the password for a specific environment
     *
     * @param environment The environment enum
     * @return The password or null if not configured
     */
    fun getPassword(environment: Environment): String? {
        return ConfigurationLoader.getProperty(getPasswordKey(environment))
    }


    /**
     * Checks if an environment has complete database configuration (enum version)
     *
     * @param environment The environment enum to check
     * @return true if all required properties are configured, false otherwise
     */
    fun hasCompleteConfiguration(environment: Environment): Boolean {
        val jdbcUrl = getJdbcUrl(environment)
        val username = getUsername(environment)
        val password = getPassword(environment)

        return !jdbcUrl.isNullOrBlank() && !username.isNullOrBlank() && !password.isNullOrBlank()
    }


    /**
     * Gets all environments that have complete database configuration (enum version)
     *
     * @return List of environments with complete configuration
     */
    fun getAvailableEnvironments(): List<Environment> {
        return Environment.getAllEnvironments()
            .filter { hasCompleteConfiguration(it) }
    }


    /**
     * Gets all configured environments with their connection details (enum version)
     *
     * @return Map of environments to their DatabaseConnectionConfig
     */
    fun getAllConfigurations(): Map<Environment, DatabaseConnectionConfig> {
        return getAvailableEnvironments().associateWith { environment ->
            createConnectionConfig(environment)
        }
    }


    /**
     * Creates a DatabaseConnectionConfig for the specified environment (enum version)
     *
     * @param environment The environment enum
     * @return DatabaseConnectionConfig for the environment
     * @throws IllegalArgumentException if the environment is not supported or not fully configured
     */
    fun createConnectionConfig(environment: Environment): DatabaseConnectionConfig {
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


}
