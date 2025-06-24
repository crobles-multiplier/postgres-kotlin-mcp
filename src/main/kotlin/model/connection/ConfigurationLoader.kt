package model.connection

import java.util.Properties

/**
 * Utility class for loading and managing configuration properties
 * 
 * Handles loading properties from configuration files and resolving environment variables.
 * Provides a centralized way to access configuration data across the application.
 */
object ConfigurationLoader {
    
    private var configCache: Properties? = null
    
    /**
     * Loads configuration from the specified properties file
     * 
     * @param configFile The name of the configuration file (default: "database.properties")
     * @return Properties object containing the loaded configuration
     * @throws IllegalStateException if the configuration file is not found
     */
    fun loadConfig(configFile: String = "database.properties"): Properties {
        return configCache ?: run {
            val properties = Properties().apply {
                ConfigurationLoader::class.java.getResourceAsStream("/$configFile")?.use { load(it) }
                    ?: throw IllegalStateException("Configuration file /$configFile not found in classpath")
            }
            configCache = properties
            properties
        }
    }
    
    /**
     * Resolves environment variables in property values
     * 
     * Supports the format ${ENV_VAR_NAME} in property values.
     * 
     * @return The resolved value or null if the environment variable is not set
     */
    private fun String.resolveEnvVar(): String? =
        if (startsWith("\${") && endsWith("}")) {
            System.getenv(substring(2, length - 1))
        } else this
    
    /**
     * Gets a property value with environment variable resolution
     * 
     * @param key The property key to retrieve
     * @param configFile The configuration file to load from (optional)
     * @return The resolved property value or null if not found
     */
    fun getProperty(key: String, configFile: String = "database.properties"): String? {
        return loadConfig(configFile).getProperty(key)?.resolveEnvVar()
    }
}
