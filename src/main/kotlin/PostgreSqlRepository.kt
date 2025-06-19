import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.*
import java.util.*
import javax.sql.DataSource

/**
 * PostgreSQL repository for database operations and relationship discovery
 */
class PostgreSqlRepository {

    private val dataSource: DataSource?
    private val jdbcUrl: String?

    // Constructor for HikariCP DataSource (preferred)
    constructor(dataSource: DataSource) {
        this.dataSource = dataSource
        this.jdbcUrl = null
    }

    // Constructor for direct connection string (legacy)
    constructor(connectionString: String) {
        this.dataSource = null
        this.jdbcUrl = if (connectionString.startsWith("jdbc:")) {
            connectionString
        } else if (connectionString.startsWith("postgresql://")) {
            // Convert postgresql://user:pass@host:port/db to jdbc:postgresql://host:port/db?user=user&password=pass
            val regex = Regex("postgresql://([^:]+):([^@]+)@([^:]+):(\\d+)/(.+)")
            val matchResult = regex.find(connectionString)
            if (matchResult != null) {
                val (user, password, host, port, database) = matchResult.destructured
                "jdbc:postgresql://$host:$port/$database?user=$user&password=$password"
            } else {
                // Fallback: simple replacement
                connectionString.replace("postgresql://", "jdbc:postgresql://")
            }
        } else {
            connectionString
        }
    }

    /**
     * Get a database connection from either DataSource or DriverManager
     */
    private fun getConnection(): Connection {
        return if (dataSource != null) {
            dataSource.connection
        } else {
            DriverManager.getConnection(jdbcUrl!!)
        }
    }
    
    /**
     * Execute a query and return results as a list of maps
     */
    suspend fun executeQuery(sql: String, maxRows: Int = 100): QueryExecutionResult = withContext(Dispatchers.IO) {
        var connection: Connection? = null
        var statement: Statement? = null
        var resultSet: ResultSet? = null
        
        try {
            connection = getConnection()
            statement = connection.createStatement()
            statement.maxRows = maxRows
            
            val startTime = System.currentTimeMillis()
            resultSet = statement.executeQuery(sql)
            val executionTime = System.currentTimeMillis() - startTime
            
            val metaData = resultSet.metaData
            val columnCount = metaData.columnCount
            val columns = (1..columnCount).map { i ->
                TableColumn(
                    name = metaData.getColumnName(i),
                    type = metaData.getColumnTypeName(i),
                    nullable = metaData.isNullable(i) == ResultSetMetaData.columnNullable
                )
            }
            
            val rows = mutableListOf<Map<String, Any?>>()
            while (resultSet.next() && rows.size < maxRows) {
                val row = mutableMapOf<String, Any?>()
                for (i in 1..columnCount) {
                    val columnName = metaData.getColumnName(i)
                    row[columnName] = resultSet.getObject(i)
                }
                rows.add(row)
            }
            
            QueryExecutionResult(
                columns = columns,
                rows = rows,
                executionTimeMs = executionTime,
                rowCount = rows.size,
                hasMoreRows = resultSet.next() // Check if there are more rows
            )
        } catch (e: SQLException) {
            throw DatabaseException("Query execution failed: ${e.message}", e)
        } finally {
            resultSet?.close()
            statement?.close()
            connection?.close()
        }
    }
    
    /**
     * Get table schema information
     */
    suspend fun getTableSchema(tableName: String): List<TableColumn> = withContext(Dispatchers.IO) {
        var connection: Connection? = null
        
        try {
            connection = getConnection()
            val metaData = connection.metaData
            val resultSet = metaData.getColumns(null, null, tableName, null)
            
            val columns = mutableListOf<TableColumn>()
            while (resultSet.next()) {
                columns.add(
                    TableColumn(
                        name = resultSet.getString("COLUMN_NAME"),
                        type = resultSet.getString("TYPE_NAME"),
                        nullable = resultSet.getInt("NULLABLE") == DatabaseMetaData.columnNullable,
                        defaultValue = resultSet.getString("COLUMN_DEF"),
                        size = resultSet.getInt("COLUMN_SIZE").takeIf { it > 0 }
                    )
                )
            }
            columns
        } catch (e: SQLException) {
            throw DatabaseException("Failed to get table schema: ${e.message}", e)
        } finally {
            connection?.close()
        }
    }
    
    /**
     * List all tables in the database
     */
    suspend fun listTables(): List<DatabaseTable> = withContext(Dispatchers.IO) {
        var connection: Connection? = null
        
        try {
            connection = getConnection()
            val metaData = connection.metaData
            val resultSet = metaData.getTables(null, null, null, arrayOf("TABLE"))
            
            val tables = mutableListOf<DatabaseTable>()
            while (resultSet.next()) {
                tables.add(
                    DatabaseTable(
                        name = resultSet.getString("TABLE_NAME"),
                        schema = resultSet.getString("TABLE_SCHEM"),
                        type = resultSet.getString("TABLE_TYPE")
                    )
                )
            }
            tables
        } catch (e: SQLException) {
            throw DatabaseException("Failed to list tables: ${e.message}", e)
        } finally {
            connection?.close()
        }
    }
    
    /**
     * Get query execution plan
     */
    suspend fun explainQuery(sql: String): String = withContext(Dispatchers.IO) {
        var connection: Connection? = null
        var statement: Statement? = null
        var resultSet: ResultSet? = null
        
        try {
            connection = getConnection()
            statement = connection.createStatement()

            val explainSql = "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) $sql"
            resultSet = statement.executeQuery(explainSql)
            
            val plans = mutableListOf<String>()
            while (resultSet.next()) {
                plans.add(resultSet.getString(1))
            }
            plans.joinToString("\n")
        } catch (e: SQLException) {
            throw DatabaseException("Failed to explain query: ${e.message}", e)
        } finally {
            resultSet?.close()
            statement?.close()
            connection?.close()
        }
    }
    
    /**
     * Get foreign key relationships for a specific table
     */
    suspend fun getForeignKeys(tableName: String): List<ForeignKeyRelationship> = withContext(Dispatchers.IO) {
        var connection: Connection? = null

        try {
            connection = getConnection()
            val metaData = connection.metaData
            val resultSet = metaData.getImportedKeys(null, null, tableName)

            val foreignKeys = mutableListOf<ForeignKeyRelationship>()
            while (resultSet.next()) {
                foreignKeys.add(
                    ForeignKeyRelationship(
                        constraintName = resultSet.getString("FK_NAME") ?: "unnamed",
                        sourceTable = resultSet.getString("FKTABLE_NAME"),
                        sourceColumn = resultSet.getString("FKCOLUMN_NAME"),
                        targetTable = resultSet.getString("PKTABLE_NAME"),
                        targetColumn = resultSet.getString("PKCOLUMN_NAME"),
                        onDelete = resultSet.getString("DELETE_RULE")?.let {
                            when (it.toInt()) {
                                DatabaseMetaData.importedKeyCascade -> "CASCADE"
                                DatabaseMetaData.importedKeySetNull -> "SET NULL"
                                DatabaseMetaData.importedKeySetDefault -> "SET DEFAULT"
                                DatabaseMetaData.importedKeyRestrict -> "RESTRICT"
                                else -> "NO ACTION"
                            }
                        },
                        onUpdate = resultSet.getString("UPDATE_RULE")?.let {
                            when (it.toInt()) {
                                DatabaseMetaData.importedKeyCascade -> "CASCADE"
                                DatabaseMetaData.importedKeySetNull -> "SET NULL"
                                DatabaseMetaData.importedKeySetDefault -> "SET DEFAULT"
                                DatabaseMetaData.importedKeyRestrict -> "RESTRICT"
                                else -> "NO ACTION"
                            }
                        }
                    )
                )
            }
            foreignKeys
        } catch (e: SQLException) {
            throw DatabaseException("Failed to get foreign keys: ${e.message}", e)
        } finally {
            connection?.close()
        }
    }

    /**
     * Get tables that reference the specified table (reverse foreign keys)
     */
    suspend fun getReferencedBy(tableName: String): List<ForeignKeyRelationship> = withContext(Dispatchers.IO) {
        var connection: Connection? = null

        try {
            connection = getConnection()
            val metaData = connection.metaData
            val resultSet = metaData.getExportedKeys(null, null, tableName)

            val referencedBy = mutableListOf<ForeignKeyRelationship>()
            while (resultSet.next()) {
                referencedBy.add(
                    ForeignKeyRelationship(
                        constraintName = resultSet.getString("FK_NAME") ?: "unnamed",
                        sourceTable = resultSet.getString("FKTABLE_NAME"),
                        sourceColumn = resultSet.getString("FKCOLUMN_NAME"),
                        targetTable = resultSet.getString("PKTABLE_NAME"),
                        targetColumn = resultSet.getString("PKCOLUMN_NAME"),
                        onDelete = resultSet.getString("DELETE_RULE")?.let {
                            when (it.toInt()) {
                                DatabaseMetaData.importedKeyCascade -> "CASCADE"
                                DatabaseMetaData.importedKeySetNull -> "SET NULL"
                                DatabaseMetaData.importedKeySetDefault -> "SET DEFAULT"
                                DatabaseMetaData.importedKeyRestrict -> "RESTRICT"
                                else -> "NO ACTION"
                            }
                        },
                        onUpdate = resultSet.getString("UPDATE_RULE")?.let {
                            when (it.toInt()) {
                                DatabaseMetaData.importedKeyCascade -> "CASCADE"
                                DatabaseMetaData.importedKeySetNull -> "SET NULL"
                                DatabaseMetaData.importedKeySetDefault -> "SET DEFAULT"
                                DatabaseMetaData.importedKeyRestrict -> "RESTRICT"
                                else -> "NO ACTION"
                            }
                        }
                    )
                )
            }
            referencedBy
        } catch (e: SQLException) {
            throw DatabaseException("Failed to get referenced by relationships: ${e.message}", e)
        } finally {
            connection?.close()
        }
    }

    /**
     * Get primary key information for a table
     */
    suspend fun getPrimaryKeys(tableName: String): List<PrimaryKeyConstraint> = withContext(Dispatchers.IO) {
        var connection: Connection? = null

        try {
            connection = getConnection()
            val metaData = connection.metaData
            val resultSet = metaData.getPrimaryKeys(null, null, tableName)

            val primaryKeys = mutableListOf<PrimaryKeyConstraint>()
            while (resultSet.next()) {
                primaryKeys.add(
                    PrimaryKeyConstraint(
                        constraintName = resultSet.getString("PK_NAME") ?: "primary_key",
                        tableName = resultSet.getString("TABLE_NAME"),
                        columnName = resultSet.getString("COLUMN_NAME"),
                        keySequence = resultSet.getInt("KEY_SEQ")
                    )
                )
            }
            primaryKeys.sortedBy { it.keySequence }
        } catch (e: SQLException) {
            throw DatabaseException("Failed to get primary keys: ${e.message}", e)
        } finally {
            connection?.close()
        }
    }

    /**
     * Get complete relationship information for a table
     */
    suspend fun getTableRelationships(tableName: String): TableRelationshipSummary = withContext(Dispatchers.IO) {
        TableRelationshipSummary(
            tableName = tableName,
            primaryKeys = getPrimaryKeys(tableName),
            foreignKeys = getForeignKeys(tableName),
            referencedBy = getReferencedBy(tableName),
            uniqueConstraints = getUniqueConstraints(tableName)
        )
    }

    /**
     * Get unique constraints for a table
     */
    suspend fun getUniqueConstraints(tableName: String): List<UniqueConstraint> = withContext(Dispatchers.IO) {
        var connection: Connection? = null
        var statement: Statement? = null
        var resultSet: ResultSet? = null

        try {
            connection = getConnection()
            statement = connection.createStatement()

            val sql = """
                SELECT
                    tc.constraint_name,
                    tc.table_name,
                    kcu.column_name
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                    ON tc.constraint_name = kcu.constraint_name
                WHERE tc.constraint_type = 'UNIQUE'
                    AND tc.table_name = ?
                ORDER BY tc.constraint_name, kcu.ordinal_position
            """.trimIndent()

            val preparedStatement = connection.prepareStatement(sql)
            preparedStatement.setString(1, tableName)
            resultSet = preparedStatement.executeQuery()

            val uniqueConstraints = mutableListOf<UniqueConstraint>()
            while (resultSet.next()) {
                uniqueConstraints.add(
                    UniqueConstraint(
                        constraintName = resultSet.getString("constraint_name"),
                        tableName = resultSet.getString("table_name"),
                        columnName = resultSet.getString("column_name")
                    )
                )
            }
            uniqueConstraints
        } catch (e: SQLException) {
            throw DatabaseException("Failed to get unique constraints: ${e.message}", e)
        } finally {
            resultSet?.close()
            statement?.close()
            connection?.close()
        }
    }

    /**
     * Generate JOIN suggestions based on foreign key relationships
     */
    suspend fun getJoinSuggestions(fromTable: String): List<JoinRecommendation> = withContext(Dispatchers.IO) {
        val suggestions = mutableListOf<JoinRecommendation>()

        // Get foreign keys from the source table
        val foreignKeys = getForeignKeys(fromTable)
        foreignKeys.forEach { fk ->
            suggestions.add(
                JoinRecommendation(
                    fromTable = fromTable,
                    toTable = fk.targetTable,
                    joinCondition = "${fromTable}.${fk.sourceColumn} = ${fk.targetTable}.${fk.targetColumn}",
                    joinType = "INNER JOIN"
                )
            )
        }

        // Get tables that reference this table
        val referencedBy = getReferencedBy(fromTable)
        referencedBy.forEach { ref ->
            suggestions.add(
                JoinRecommendation(
                    fromTable = fromTable,
                    toTable = ref.sourceTable,
                    joinCondition = "${fromTable}.${ref.targetColumn} = ${ref.sourceTable}.${ref.sourceColumn}",
                    joinType = "INNER JOIN"
                )
            )
        }

        suggestions
    }

    /**
     * Get database name and connection information
     */
    suspend fun getDatabaseInfo(): DatabaseInfo = withContext(Dispatchers.IO) {
        var connection: Connection? = null
        try {
            connection = getConnection()
            val metaData = connection.metaData

            DatabaseInfo(
                databaseName = connection.catalog ?: "unknown",
                databaseVersion = metaData.databaseProductVersion,
                databaseProduct = metaData.databaseProductName,
                driverName = metaData.driverName,
                driverVersion = metaData.driverVersion,
                url = metaData.url,
                username = metaData.userName
            )
        } catch (e: SQLException) {
            throw DatabaseException("Failed to get database info: ${e.message}", e)
        } finally {
            connection?.close()
        }
    }

    /**
     * Test database connection
     */
    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        var connection: Connection? = null
        try {
            if (dataSource != null) {
                System.err.println("Testing HikariCP DataSource connection")
            } else {
                System.err.println("Attempting JDBC connection to: $jdbcUrl")
            }
            connection = getConnection()
            val isValid = connection.isValid(5) // 5 second timeout
            System.err.println("Connection valid: $isValid")
            isValid
        } catch (e: SQLException) {
            System.err.println("SQLException: ${e.message}")
            e.printStackTrace()
            false
        } catch (e: Exception) {
            System.err.println("General exception: ${e.message}")
            e.printStackTrace()
            false
        } finally {
            connection?.close()
        }
    }
}

/**
 * Data classes for query results
 */
data class QueryExecutionResult(
    val columns: List<TableColumn>,
    val rows: List<Map<String, Any?>>,
    val executionTimeMs: Long,
    val rowCount: Int,
    val hasMoreRows: Boolean
)

data class TableColumn(
    val name: String,
    val type: String,
    val nullable: Boolean,
    val defaultValue: String? = null,
    val size: Int? = null
)

data class DatabaseTable(
    val name: String,
    val schema: String?,
    val type: String
)

data class DatabaseInfo(
    val databaseName: String,
    val databaseVersion: String,
    val databaseProduct: String,
    val driverName: String,
    val driverVersion: String,
    val url: String,
    val username: String
)

/**
 * Data classes for relationship information
 */
data class ForeignKeyRelationship(
    val constraintName: String,
    val sourceTable: String,
    val sourceColumn: String,
    val targetTable: String,
    val targetColumn: String,
    val onDelete: String?,
    val onUpdate: String?
)

data class PrimaryKeyConstraint(
    val constraintName: String,
    val tableName: String,
    val columnName: String,
    val keySequence: Int
)

data class UniqueConstraint(
    val constraintName: String,
    val tableName: String,
    val columnName: String
)

data class TableRelationshipSummary(
    val tableName: String,
    val primaryKeys: List<PrimaryKeyConstraint>,
    val foreignKeys: List<ForeignKeyRelationship>,
    val referencedBy: List<ForeignKeyRelationship>,
    val uniqueConstraints: List<UniqueConstraint>
)

data class JoinRecommendation(
    val fromTable: String,
    val toTable: String,
    val joinCondition: String,
    val joinType: String = "INNER JOIN"
)

/**
 * Custom exception for database operations
 */
class DatabaseException(message: String, cause: Throwable? = null) : Exception(message, cause)
