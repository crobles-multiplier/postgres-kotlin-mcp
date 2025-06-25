import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import model.Environment
import model.connection.ConfigurationLoader
import model.connection.DatabaseConnectionConfig
import model.connection.ReconnectionResult
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * HikariCP-based Database Connection Manager
 *
 * Manages multiple PostgreSQL database connections for different environments using HikariCP.
 * Provides enterprise-grade connection pooling, health monitoring, and resource management.
 */
class HikariConnectionManager {

    private val dataSources = ConcurrentHashMap<Environment, model.connection.DataSourceInfo>()
    private var isEnvironmentMode = false

    /**
     * Initialize connections for all configured environments using HikariCP
     */
    suspend fun initializeEnvironments() {
        val environments = Environment.getAllEnvironments()
        var successfulConnections = 0

        for (environment in environments) {
            try {
                val dbConfig = DatabaseConnectionConfig.forEnvironment(environment)
                val dataSource = createHikariDataSource(dbConfig)
                val repository = PostgreSqlRepository(dataSource)

                // Test the connection
                val isConnected = withTimeout(5000) {
                    repository.testConnection()
                }

                if (isConnected) {
                    val dataSourceInfo = model.connection.DataSourceInfo(
                        dataSource = dataSource,
                        environment = environment,
                        repository = repository
                    )
                    dataSources[environment] = dataSourceInfo
                    successfulConnections++
                    System.err.println("✓ Connected to ${environment.value} database (${dbConfig.getHost()}:${dbConfig.getPort()}/${dbConfig.getDatabaseName()}) - HikariCP pool: ${dataSource.maximumPoolSize} max connections")
                } else {
                    dataSource.close()
                    System.err.println("✗ Failed to connect to ${environment.value} database")
                }
            } catch (e: IllegalArgumentException) {
                System.err.println("⚠ Configuration error for ${environment.value}: ${e.message}")
            } catch (e: TimeoutCancellationException) {
                System.err.println("✗ Connection timeout for ${environment.value} database")
            } catch (e: Exception) {
                System.err.println("✗ Error connecting to ${environment.value} database: ${e.message}")
            }
        }

        if (successfulConnections == 0) {
            System.err.println(
                """
                ⚠️  WARNING: No database connections could be established at startup.

                🔄 This is not a fatal error - the MCP server will start anyway.
                You can use the 'postgres_reconnect_database' tool to establish connections later.

                Common reasons for connection failures:
                • VPN is not connected
                • Database servers are temporarily unavailable
                • Network connectivity issues
                • Configuration needs to be updated

                🔧 To establish connections later:
                • Use: postgres_reconnect_database (reconnects all environments)
                • Use: postgres_reconnect_database with environment parameter (reconnects specific environment)

                📋 Configuration check - ensure your database.properties file contains:
                database.staging.jdbc-url=${'$'}{POSTGRES_STAGING_JDBC_URL}
                database.staging.username=${'$'}{POSTGRES_STAGING_USERNAME}
                database.staging.password=${'$'}{POSTGRES_STAGING_PASSWORD}

                🌐 Set environment variables:
                export POSTGRES_STAGING_JDBC_URL="jdbc:postgresql://localhost:5432/database_name"
                export POSTGRES_STAGING_USERNAME="your_username"
                export POSTGRES_STAGING_PASSWORD="your_password"
            """.trimIndent()
            )
        }

        isEnvironmentMode = true
        System.err.println("Initialized $successfulConnections HikariCP connection pool(s)")
    }

    /**
     * Create a HikariCP DataSource using DatabaseConnectionConfig DTO
     */
    private fun createHikariDataSource(dbConfig: DatabaseConnectionConfig): HikariDataSource {

        val config = HikariConfig().apply {
            // Database connection properties - directly from validated DTO
            this.jdbcUrl = dbConfig.jdbcUrl
            this.username = dbConfig.username
            this.password = dbConfig.password

            // Driver class name - use default for PostgreSQL
            this.driverClassName = "org.postgresql.Driver"

            // Connection pool settings - use sensible defaults with optional overrides
            maximumPoolSize = getConfigValueOrNull(dbConfig.environment, "maximum-pool-size")?.toIntOrNull() ?: 10
            minimumIdle = getConfigValueOrNull(dbConfig.environment, "minimum-idle")?.toIntOrNull() ?: 2

            // Health check settings
            connectionTestQuery = "SELECT 1"
            isAutoCommit = true

            // Connection timeout - 5 seconds
            connectionTimeout = 5000

            // Pool name for monitoring
            poolName = "PostgreSQL-${dbConfig.environment}"

            // Performance optimizations for read-only queries
            isReadOnly = true // We only do SELECT queries
            transactionIsolation = "TRANSACTION_READ_COMMITTED"

            // Metrics and monitoring
            isRegisterMbeans = true
        }

        return HikariDataSource(config)
    }


    /**
     * Get optional HikariCP configuration value with fallback to environment variables
     */
    private fun getConfigValueOrNull(environment: Environment, setting: String): String? {
        // Try environment-specific setting first: hikari.staging.maximum-pool-size
        val envSpecificKey = "hikari.${environment.value}.$setting"
        val envSpecificValue = ConfigurationLoader.getProperty(envSpecificKey)
        if (!envSpecificValue.isNullOrBlank()) {
            return envSpecificValue
        }

        // Try global hikari setting: hikari.maximum-pool-size
        val globalKey = "hikari.$setting"
        return ConfigurationLoader.getProperty(globalKey)
    }

    /**
     * Get database connection for the specified environment (enum version)
     */
    fun getConnection(environment: Environment): PostgreSqlRepository {
        val dataSourceInfo = dataSources[environment]
            ?: throw Exception(
                """
                No database connection available for environment: ${environment.value}

                🔄 Use the 'postgres_reconnect_database' tool to establish a connection:
                • To reconnect this environment: postgres_reconnect_database with environment="${environment.value}"
                • To reconnect all environments: postgres_reconnect_database

                💡 This usually happens when:
                • VPN connection was lost and restored
                • Database server was temporarily unavailable
                • Network connectivity issues occurred
                • MCP server started without valid connections
            """.trimIndent()
            )

        // Update last used time
        val updatedInfo = dataSourceInfo.copy(lastUsed = Instant.now())
        dataSources[environment] = updatedInfo

        return updatedInfo.repository
    }

    /**
     * Get database connection for the specified environment (string version)
     */
    fun getConnection(environment: String?): PostgreSqlRepository {
        val targetEnv = environment?.let { Environment.fromString(it) } ?: Environment.getDefault()
        return getConnection(targetEnv)
    }

    /**
     * Get list of available environments
     */
    fun getAvailableEnvironments(): List<String> {
        return if (isEnvironmentMode) {
            dataSources.keys.map { it.value }
        } else {
            listOf("default")
        }
    }


    /**
     * Get connection statistics from HikariCP pools
     */
    fun getConnectionStats(): Map<String, Any> {
        val stats = mutableMapOf<String, Any>()
        stats["total_environments"] = dataSources.size
        stats["environment_mode"] = isEnvironmentMode
        stats["available_environments"] = getAvailableEnvironments()

        if (dataSources.isEmpty()) {
            stats["status"] = "no_connections"
            stats["message"] =
                "No database connections are currently established. Use 'postgres_reconnect_database' to establish connections."
            stats["pool_details"] = emptyMap<String, Any>()
            stats["summary"] = mapOf(
                "total_active_connections" to 0,
                "total_idle_connections" to 0,
                "total_maximum_connections" to 0,
                "connection_utilization_percent" to 0
            )
        } else {
            stats["status"] = "connected"

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
        }

        return stats
    }


    /**
     * Test connection health for a specific environment (enum version)
     */
    suspend fun testEnvironmentConnection(environment: Environment): Boolean {
        return testEnvironmentConnectionInternal(environment)
    }

    /**
     * Test connection health for a specific environment (string version)
     */
    suspend fun testEnvironmentConnection(environment: String): Boolean {
        val envEnum = Environment.fromString(environment)
        return testEnvironmentConnection(envEnum)
    }

    /**
     * Test connection health for a specific environment (enum version - internal)
     */
    private suspend fun testEnvironmentConnectionInternal(environment: Environment): Boolean {
        val dataSourceInfo = dataSources[environment] ?: return false

        return try {
            withTimeout(5000) {
                dataSourceInfo.repository.testConnection()
            }
        } catch (e: Exception) {
            System.err.println("Connection test failed for ${environment.value}: ${e.message}")
            false
        }
    }

    /**
     * Get connection health status for all environments
     */
    suspend fun getConnectionHealth(): Map<String, Boolean> {
        val healthStatus = mutableMapOf<String, Boolean>()

        for (environment in dataSources.keys) {
            healthStatus[environment.value] = testEnvironmentConnectionInternal(environment)
        }

        return healthStatus
    }

    /**
     * Reconnect to a specific environment database (enum version)
     */
    suspend fun reconnectEnvironment(environment: Environment): ReconnectionResult {
        return reconnectEnvironmentInternal(environment)
    }

    /**
     * Reconnect to a specific environment database (string version)
     * Useful when VPN connection is restored or database connectivity issues are resolved
     */
    suspend fun reconnectEnvironment(environment: String): ReconnectionResult {
        val envEnum = Environment.fromString(environment)
        return reconnectEnvironment(envEnum)
    }

    /**
     * Reconnect to a specific environment database (enum version - internal)
     */
    private suspend fun reconnectEnvironmentInternal(environment: Environment): ReconnectionResult {
        System.err.println("Attempting to reconnect to ${environment.value} database...")

        return try {
            // Close existing connection if it exists
            dataSources[environment]?.let { existingInfo ->
                try {
                    existingInfo.dataSource.close()
                    System.err.println("Closed existing connection pool for ${environment.value}")
                } catch (e: Exception) {
                    System.err.println("Warning: Error closing existing connection for ${environment.value}: ${e.message}")
                }
            }

            // Remove from active connections
            dataSources.remove(environment)

            // Create new connection
            val dbConfig = DatabaseConnectionConfig.forEnvironment(environment)
            val dataSource = createHikariDataSource(dbConfig)
            val repository = PostgreSqlRepository(dataSource)

            // Test the new connection
            val isConnected = withTimeout(5000) {
                repository.testConnection()
            }

            if (isConnected) {
                val dataSourceInfo = model.connection.DataSourceInfo(
                    dataSource = dataSource,
                    environment = environment,
                    repository = repository
                )
                dataSources[environment] = dataSourceInfo

                val successMessage =
                    "✓ Successfully reconnected to ${environment.value} database (${dbConfig.getHost()}:${dbConfig.getPort()}/${dbConfig.getDatabaseName()})"
                System.err.println(successMessage)

                ReconnectionResult(
                    success = true,
                    environment = environment,
                    message = successMessage,
                    connectionInfo = "${dbConfig.getHost()}:${dbConfig.getPort()}/${dbConfig.getDatabaseName()}"
                )
            } else {
                dataSource.close()
                val errorMessage = "✗ Failed to establish connection to ${environment.value} database"
                System.err.println(errorMessage)

                ReconnectionResult(
                    success = false,
                    environment = environment,
                    message = errorMessage,
                    error = "Connection test failed"
                )
            }
        } catch (e: IllegalArgumentException) {
            val errorMessage = "⚠ Configuration error for ${environment.value}: ${e.message}"
            System.err.println(errorMessage)
            ReconnectionResult(
                success = false,
                environment = environment,
                message = errorMessage,
                error = e.message
            )
        } catch (e: TimeoutCancellationException) {
            val errorMessage = "✗ Connection timeout for ${environment.value} database"
            System.err.println(errorMessage)
            ReconnectionResult(
                success = false,
                environment = environment,
                message = errorMessage,
                error = "Connection timeout"
            )
        } catch (e: Exception) {
            val errorMessage = "✗ Error reconnecting to ${environment.value} database: ${e.message}"
            System.err.println(errorMessage)
            ReconnectionResult(
                success = false,
                environment = environment,
                message = errorMessage,
                error = e.message
            )
        }
    }

    /**
     * Reconnect to all configured environments
     * Useful for bulk reconnection after VPN reconnection or network issues
     */
    suspend fun reconnectAllEnvironments(): List<ReconnectionResult> {
        val environments = Environment.getAllEnvironments()
        val results = mutableListOf<ReconnectionResult>()

        System.err.println("Attempting to reconnect to all configured environments...")

        for (environment in environments) {
            val result = reconnectEnvironmentInternal(environment)
            results.add(result)
        }

        val successCount = results.count { it.success }
        val totalCount = results.size

        System.err.println("Reconnection complete: $successCount/$totalCount environments successfully connected")

        return results
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
