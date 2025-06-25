package model

/**
 * Supported database environments for the PostgreSQL MCP tool
 *
 * This enum defines all valid database environments that the application can connect to.
 * Each environment has a string value that corresponds to configuration keys and user input.
 *
 * Usage:
 * - Environment.STAGING.value -> "staging"
 * - Environment.fromString("production") -> Environment.PRODUCTION
 * - Environment.getSupportedEnvironments() -> ["staging", "release", "production"]
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
         * Gets all supported environment names as strings
         *
         * @return List of environment string values
         */
        fun getSupportedEnvironments(): List<String> {
            return values().map { it.value }
        }

        /**
         * Gets all supported environments as enum values
         *
         * @return List of Environment enum values
         */
        fun getAllEnvironments(): List<Environment> {
            return values().toList()
        }

        /**
         * Gets the default environment (STAGING)
         *
         * @return The default Environment enum value
         */
        fun getDefault(): Environment = STAGING


    }

    /**
     * Returns the string value of this environment
     */
    override fun toString(): String = value
}
