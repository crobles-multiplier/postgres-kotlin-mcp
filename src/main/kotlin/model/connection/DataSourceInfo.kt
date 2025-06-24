package model.connection

import PostgreSqlRepository
import com.zaxxer.hikari.HikariDataSource
import java.time.Instant

/**
 * Data class containing information about a HikariCP data source and its associated repository
 *
 * This class encapsulates the connection pool information for a specific database environment,
 * including the HikariCP data source, environment identifier, repository instance, and usage tracking.
 *
 * @property dataSource HikariCP data source for database connections
 * @property environment Environment identifier (e.g., "staging", "release", "production")
 * @property repository PostgreSQL repository instance associated with this data source
 * @property lastUsed Timestamp of when this data source was last accessed
 */
data class DataSourceInfo(
    val dataSource: HikariDataSource,
    val environment: String,
    val repository: PostgreSqlRepository,
    val lastUsed: Instant = Instant.now()
)
