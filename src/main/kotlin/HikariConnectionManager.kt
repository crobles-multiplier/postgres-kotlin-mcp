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
            try {
                val dbConfig = DatabaseConnectionConfig.forEnvironment(environment)
                val dataSource = createHikariDataSource(dbConfig)
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
                    System.err.println("✓ Connected to $environment database (${dbConfig.getHost()}:${dbConfig.getPort()}/${dbConfig.getDatabaseName()}) - HikariCP pool: ${dataSource.maximumPoolSize} max connections")
                } else {
                    dataSource.close()
                    System.err.println("✗ Failed to connect to $environment database")
                }
            } catch (e: IllegalArgumentException) {
                System.err.println("⚠ Configuration error for $environment: ${e.message}")
            } catch (e: TimeoutCancellationException) {
                System.err.println("✗ Connection timeout for $environment database")
            } catch (e: Exception) {
                System.err.println("✗ Error connecting to $environment database: ${e.message}")
            }
        }
        
        if (successfulConnections == 0) {
            throw Exception("""
                No database connections could be established.

                Please check your database.properties configuration file and ensure:
                1. At least one environment is configured (staging, release, or production)
                2. All required database connection properties are provided for each environment
                3. Database credentials are correct and accessible

                Example configuration in database.properties:
                database.staging.jdbc-url=${'$'}{POSTGRES_STAGING_JDBC_URL}
                database.staging.username=${'$'}{POSTGRES_STAGING_USERNAME}
                database.staging.password=${'$'}{POSTGRES_STAGING_PASSWORD}

                Set environment variables:
                export POSTGRES_STAGING_JDBC_URL="jdbc:postgresql://localhost:5432/database_name"
                export POSTGRES_STAGING_USERNAME="your_username"
                export POSTGRES_STAGING_PASSWORD="your_password"
            """.trimIndent())
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
    private fun getConfigValueOrNull(environment: String, setting: String): String? {
        // Try environment-specific setting first: hikari.staging.maximum-pool-size
        val envSpecificKey = "hikari.$environment.$setting"
        val envSpecificValue = DatabaseConnectionConfig.getProperty(envSpecificKey)
        if (!envSpecificValue.isNullOrBlank()) {
            return envSpecificValue
        }

        // Try global hikari setting: hikari.maximum-pool-size
        val globalKey = "hikari.$setting"
        return DatabaseConnectionConfig.getProperty(globalKey)
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
