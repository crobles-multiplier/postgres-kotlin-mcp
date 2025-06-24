package model.database

/**
 * Data class containing database connection and metadata information
 *
 * @property databaseName Name of the database
 * @property databaseVersion Version of the database server
 * @property databaseProduct Database product name (e.g., "PostgreSQL")
 * @property driverName JDBC driver name
 * @property driverVersion JDBC driver version
 * @property url Database connection URL
 * @property username Database username used for connection
 */
data class DatabaseInfo(
    val databaseName: String,
    val databaseVersion: String,
    val databaseProduct: String,
    val driverName: String,
    val driverVersion: String,
    val url: String,
    val username: String
)
