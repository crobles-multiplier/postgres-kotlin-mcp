import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.*
import java.sql.Connection
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

/**
 * HikariCP-based Database Connection Manager
 * 
 * Manages multiple PostgreSQL database connections for different environments using HikariCP.
 * Provides enterprise-grade connection pooling, health monitoring, and resource management.
 */
class HikariConnectionManager {
    
    private data class DataSourceInfo(
        val dataSource: HikariDataSource,
        val environment: String,
        val repository: PostgreSqlRepository,
        val lastUsed: Instant = Instant.now()
    )
    
    private val dataSources = ConcurrentHashMap<String, DataSourceInfo>()
    private var isEnvironmentMode = false
    
    /**
     * Initialize connections for all configured environments using HikariCP
     */
    suspend fun initializeEnvironments() {
        val environments = listOf("staging", "release", "production")
        var successfulConnections = 0
        
        for (environment in environments) {
            val connectionString = DatabaseConfiguration.getConnectionString(environment)
            if (connectionString != null) {
                try {
                    val dataSource = createHikariDataSource(environment, connectionString)
                    val repository = PostgreSqlRepository(dataSource)
                    
                    // Test the connection
                    val isConnected = withTimeout(10000) {
                        repository.testConnection()
                    }
                    
                    if (isConnected) {
                        val dataSourceInfo = DataSourceInfo(
                            dataSource = dataSource,
                            environment = environment,
                            repository = repository
                        )
                        dataSources[environment] = dataSourceInfo
                        successfulConnections++
                        System.err.println("✓ Connected to $environment database (HikariCP pool: ${dataSource.maximumPoolSize} max connections)")
                    } else {
                        dataSource.close()
                        System.err.println("✗ Failed to connect to $environment database")
                    }
                } catch (e: TimeoutCancellationException) {
                    System.err.println("✗ Connection timeout for $environment database")
                } catch (e: Exception) {
                    System.err.println("✗ Error connecting to $environment database: ${e.message}")
                }
            } else {
                System.err.println("⚠ No connection string configured for $environment environment")
            }
        }
        
        if (successfulConnections == 0) {
            throw Exception("""
                No database connections could be established.
                
                Please check your database.properties configuration file and ensure:
                1. At least one environment is configured (staging, release, or production)
                2. Connection strings are valid and accessible
                3. Database credentials are correct
                
                Example configuration:
                database.staging.url=postgresql://username:password@localhost:5432/database_name
                database.release.url=${'$'}{POSTGRES_RELEASE_CONNECTION_STRING}
                database.production.url=${'$'}{POSTGRES_PRODUCTION_CONNECTION_STRING}
            """.trimIndent())
        }
        
        isEnvironmentMode = true
        System.err.println("Initialized $successfulConnections HikariCP connection pool(s)")
    }
    
    /**
     * Create a HikariCP DataSource with strict configuration validation from database.properties
     */
    private fun createHikariDataSource(environment: String, connectionString: String): HikariDataSource {
        // Validate all required HikariCP configuration before creating DataSource
        validateHikariConfiguration(environment)

        val config = HikariConfig().apply {
            // Convert postgresql:// URLs to jdbc:postgresql:// format for HikariCP
            this.jdbcUrl = convertToJdbcUrl(connectionString)

            // Extract database credentials from connection string for HikariCP
            val (username, password) = extractCredentialsFromUrl(connectionString)
            this.username = username
            this.password = password

            // Driver class name - required property
            this.driverClassName = getRequiredConfigValue(environment, "driver-class-name")

            // Connection pool settings - all required
            maximumPoolSize = getRequiredConfigValue(environment, "maximum-pool-size").toInt()
            minimumIdle = getRequiredConfigValue(environment, "minimum-idle").toInt()

            // Timeout settings - all required
            connectionTimeout = getRequiredConfigValue(environment, "connection-timeout").toLong()
            idleTimeout = getRequiredConfigValue(environment, "idle-timeout").toLong()
            maxLifetime = getRequiredConfigValue(environment, "max-lifetime").toLong()
            validationTimeout = getRequiredConfigValue(environment, "validation-timeout").toLong()

            // Health check settings
            connectionTestQuery = "SELECT 1"
            isAutoCommit = true

            // Pool name for monitoring
            poolName = "PostgreSQL-$environment"

            // Leak detection - required for non-production environments
            if (environment != "production") {
                leakDetectionThreshold = getRequiredConfigValue(environment, "leak-detection-threshold").toLong()
            }

            // Performance optimizations
            isReadOnly = true // We only do SELECT queries
            transactionIsolation = "TRANSACTION_READ_COMMITTED"

            // Metrics and monitoring
            isRegisterMbeans = true
        }

        return HikariDataSource(config)
    }

    /**
     * Validate that all required HikariCP configuration properties are present
     */
    private fun validateHikariConfiguration(environment: String) {
        val requiredProperties = listOf(
            "driver-class-name",
            "maximum-pool-size",
            "minimum-idle",
            "connection-timeout",
            "idle-timeout",
            "max-lifetime",
            "validation-timeout"
        )

        // Add leak detection threshold for non-production environments
        val allRequiredProperties = if (environment != "production") {
            requiredProperties + "leak-detection-threshold"
        } else {
            requiredProperties
        }

        val missingProperties = mutableListOf<String>()

        for (property in allRequiredProperties) {
            val value = getConfigValueOrNull(environment, property)
            if (value.isNullOrBlank()) {
                missingProperties.add(property)
            }
        }

        if (missingProperties.isNotEmpty()) {
            val envSpecificKeys = missingProperties.map { "hikari.$environment.$it" }
            val globalKeys = missingProperties.map { "hikari.$it" }

            throw Exception("""
                Missing required HikariCP configuration properties for environment '$environment'.

                The following properties are required but not found in database.properties:
                ${missingProperties.joinToString("\n") { "  - $it" }}

                Please add these properties to your database.properties file using either:

                Environment-specific format:
                ${envSpecificKeys.joinToString("\n") { "  $it=<value>" }}

                Or global format:
                ${globalKeys.joinToString("\n") { "  $it=<value>" }}

                Example configuration:
                hikari.driver-class-name=org.postgresql.Driver
                hikari.maximum-pool-size=10
                hikari.minimum-idle=2
                hikari.connection-timeout=30000
                hikari.idle-timeout=600000
                hikari.max-lifetime=1800000
                hikari.validation-timeout=5000
                ${if (environment != "production") "hikari.leak-detection-threshold=60000" else ""}
            """.trimIndent())
        }
    }

    /**
     * Convert PostgreSQL URL to JDBC format for HikariCP
     */
    private fun convertToJdbcUrl(connectionString: String): String {
        return if (connectionString.startsWith("jdbc:")) {
            connectionString
        } else if (connectionString.startsWith("postgresql://")) {
            // Convert postgresql://user:pass@host:port/db to jdbc:postgresql://host:port/db
            val regex = Regex("postgresql://([^:]+):([^@]+)@([^:]+):(\\d+)/(.+)")
            val matchResult = regex.find(connectionString)
            if (matchResult != null) {
                val (_, _, host, port, database) = matchResult.destructured
                "jdbc:postgresql://$host:$port/$database"
            } else {
                // Fallback: simple replacement
                connectionString.replace("postgresql://", "jdbc:postgresql://")
            }
        } else {
            connectionString
        }
    }

    /**
     * Extract username and password from PostgreSQL connection string
     */
    private fun extractCredentialsFromUrl(connectionString: String): Pair<String, String> {
        val regex = Regex("postgresql://([^:]+):([^@]+)@")
        val matchResult = regex.find(connectionString)
        if (matchResult != null) {
            val (username, password) = matchResult.destructured
            return Pair(username, password)
        }
        throw Exception("Unable to extract credentials from connection string. Expected format: postgresql://username:password@host:port/database")
    }
    
    /**
     * Get HikariCP configuration value from database.properties without fallback defaults
     * Returns null if the property is not found
     */
    private fun getConfigValueOrNull(environment: String, setting: String): String? {
        // Try environment-specific setting first: hikari.staging.maximum-pool-size
        val envSpecificKey = "hikari.$environment.$setting"
        val envSpecificValue = DatabaseConfiguration.getProperty(envSpecificKey)
        if (!envSpecificValue.isNullOrBlank()) {
            return envSpecificValue
        }

        // Try global hikari setting: hikari.maximum-pool-size
        val globalKey = "hikari.$setting"
        val globalValue = DatabaseConfiguration.getProperty(globalKey)
        if (!globalValue.isNullOrBlank()) {
            return globalValue
        }

        // Try environment variable override: HIKARI_STAGING_MAXIMUM_POOL_SIZE
        val envVarKey = "HIKARI_${environment.uppercase()}_${setting.replace("-", "_").uppercase()}"
        val envVarValue = System.getenv(envVarKey)
        if (!envVarValue.isNullOrBlank()) {
            return envVarValue
        }

        // Try global environment variable: HIKARI_MAXIMUM_POOL_SIZE
        val globalEnvVarKey = "HIKARI_${setting.replace("-", "_").uppercase()}"
        val globalEnvVarValue = System.getenv(globalEnvVarKey)
        if (!globalEnvVarValue.isNullOrBlank()) {
            return globalEnvVarValue
        }

        return null
    }

    /**
     * Get required HikariCP configuration value - throws exception if not found
     */
    private fun getRequiredConfigValue(environment: String, setting: String): String {
        val value = getConfigValueOrNull(environment, setting)
        if (value.isNullOrBlank()) {
            throw Exception("""
                Required HikariCP configuration property '$setting' not found for environment '$environment'.

                Please add one of the following to your database.properties file:
                  hikari.$environment.$setting=<value>
                  hikari.$setting=<value>

                Or set environment variable:
                  HIKARI_${environment.uppercase()}_${setting.replace("-", "_").uppercase()}=<value>
                  HIKARI_${setting.replace("-", "_").uppercase()}=<value>
            """.trimIndent())
        }
        return value
    }
    
    /**
     * Get database connection for the specified environment
     */
    fun getConnection(environment: String?): PostgreSqlRepository {
        val targetEnv = environment ?: "staging"
        
        val dataSourceInfo = dataSources[targetEnv]
            ?: throw Exception("No database connection available for environment: $targetEnv")
        
        // Update last used time
        val updatedInfo = dataSourceInfo.copy(lastUsed = Instant.now())
        dataSources[targetEnv] = updatedInfo
        
        return updatedInfo.repository
    }
    
    /**
     * Get list of available environments
     */
    fun getAvailableEnvironments(): List<String> {
        return if (isEnvironmentMode) {
            dataSources.keys.toList()
        } else {
            listOf("default")
        }
    }
    
    /**
     * Check if environment mode is enabled
     */
    fun isEnvironmentModeEnabled(): Boolean = isEnvironmentMode
    
    /**
     * Get connection statistics from HikariCP pools
     */
    fun getConnectionStats(): Map<String, Any> {
        val stats = mutableMapOf<String, Any>()
        stats["total_environments"] = dataSources.size
        stats["environment_mode"] = isEnvironmentMode
        stats["available_environments"] = getAvailableEnvironments()
        
        val poolStats = dataSources.mapValues { (env, info) ->
            val poolMXBean = info.dataSource.hikariPoolMXBean
            mapOf(
                "pool_name" to info.dataSource.poolName,
                "active_connections" to poolMXBean.activeConnections,
                "idle_connections" to poolMXBean.idleConnections,
                "total_connections" to poolMXBean.totalConnections,
                "threads_awaiting_connection" to poolMXBean.threadsAwaitingConnection,
                "maximum_pool_size" to info.dataSource.maximumPoolSize,
                "minimum_idle" to info.dataSource.minimumIdle,
                "connection_timeout" to info.dataSource.connectionTimeout,
                "idle_timeout" to info.dataSource.idleTimeout,
                "max_lifetime" to info.dataSource.maxLifetime,
                "last_used" to info.lastUsed.toString(),
                "is_closed" to info.dataSource.isClosed
            )
        }
        stats["pool_details"] = poolStats
        
        // Overall health summary
        val totalActive = dataSources.values.sumOf { it.dataSource.hikariPoolMXBean.activeConnections }
        val totalIdle = dataSources.values.sumOf { it.dataSource.hikariPoolMXBean.idleConnections }
        val totalMax = dataSources.values.sumOf { it.dataSource.maximumPoolSize }
        
        stats["summary"] = mapOf(
            "total_active_connections" to totalActive,
            "total_idle_connections" to totalIdle,
            "total_maximum_connections" to totalMax,
            "connection_utilization_percent" to if (totalMax > 0) (totalActive * 100 / totalMax) else 0
        )
        
        return stats
    }
    
    /**
     * Get connection info for debugging
     */
    fun getConnectionInfo(): String {
        return if (isEnvironmentMode) {
            val totalActive = dataSources.values.sumOf { it.dataSource.hikariPoolMXBean.activeConnections }
            val totalMax = dataSources.values.sumOf { it.dataSource.maximumPoolSize }
            "HikariCP mode: ${dataSources.keys.joinToString(", ")} ($totalActive/$totalMax connections active)"
        } else {
            "Single database mode"
        }
    }
    
    /**
     * Shutdown all connection pools and cleanup resources
     */
    fun shutdown() {
        System.err.println("Shutting down HikariCP connection pools...")
        
        dataSources.values.forEach { dataSourceInfo ->
            try {
                dataSourceInfo.dataSource.close()
                System.err.println("Closed connection pool for ${dataSourceInfo.environment}")
            } catch (e: Exception) {
                System.err.println("Error closing connection pool for ${dataSourceInfo.environment}: ${e.message}")
            }
        }
        
        dataSources.clear()
        System.err.println("HikariCP connection manager shutdown complete")
    }
}
