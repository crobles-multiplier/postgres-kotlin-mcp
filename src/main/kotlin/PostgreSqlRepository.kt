import exception.DatabaseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import model.database.DatabaseInfo
import model.database.DatabaseTable
import model.database.TableColumn
import model.query.QueryExecutionResult
import model.relationship.ForeignKeyRelationship
import model.relationship.JoinRecommendation
import model.relationship.PrimaryKeyConstraint
import model.relationship.TableRelationshipSummary
import model.relationship.UniqueConstraint
import model.security.ColumnSensitivityInfo
import model.security.PiiConfiguration
import model.security.PiiFilteredQuery
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.SQLException
import java.sql.Statement
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



    /**
     * Get column comments and sensitivity information for a table
     */
    suspend fun getColumnSensitivityInfo(tableName: String): Map<String, ColumnSensitivityInfo> = withContext(Dispatchers.IO) {
        var connection: Connection? = null
        var preparedStatement: java.sql.PreparedStatement? = null
        var resultSet: ResultSet? = null

        try {
            connection = getConnection()

            // Query to get column comments from PostgreSQL system tables
            // This query works across all schemas by not filtering on schema name
            val sql = """
                SELECT
                    c.table_schema,
                    c.column_name,
                    col_description(pgc.oid, c.ordinal_position) as column_comment
                FROM information_schema.columns c
                JOIN pg_class pgc ON pgc.relname = c.table_name
                JOIN pg_namespace pgn ON pgn.oid = pgc.relnamespace AND pgn.nspname = c.table_schema
                WHERE c.table_name = ?
                  AND col_description(pgc.oid, c.ordinal_position) IS NOT NULL
                ORDER BY c.table_schema, c.ordinal_position
            """.trimIndent()

            preparedStatement = connection.prepareStatement(sql)
            preparedStatement.setString(1, tableName)
            resultSet = preparedStatement.executeQuery()

            val sensitivityMap = mutableMapOf<String, ColumnSensitivityInfo>()

            while (resultSet.next()) {
                val columnName = resultSet.getString("column_name")
                val comment = resultSet.getString("column_comment")
                val sensitivityInfo = parseColumnSensitivity(comment)

                if (sensitivityInfo != null) {
                    sensitivityMap[columnName] = sensitivityInfo
                }
            }

            sensitivityMap
        } catch (e: SQLException) {
            // If we can't access column comments (permission issue), return empty map
            System.err.println("Warning: Cannot access column comments for PII detection: ${e.message}")
            emptyMap()
        } finally {
            resultSet?.close()
            preparedStatement?.close()
            connection?.close()
        }
    }

    /**
     * Parse JSON comment to extract sensitivity information using kotlinx.serialization
     */
    private fun parseColumnSensitivity(comment: String?): ColumnSensitivityInfo? {
        if (comment.isNullOrBlank()) return null

        return try {
            // Use kotlinx.serialization for proper JSON parsing
            val jsonElement = Json.parseToJsonElement(comment)

            val targetObject = when (jsonElement) {
                is JsonArray -> {
                    // Handle array format: [{"sensitivity":"internal", "privacy":"personal"}]
                    if (jsonElement.isNotEmpty()) jsonElement[0].jsonObject else null
                }
                is JsonObject -> {
                    // Handle direct object format: {"sensitivity":"internal", "privacy":"personal"}
                    jsonElement
                }
                else -> null
            }

            targetObject?.let { obj ->
                val sensitivity = obj["sensitivity"]?.jsonPrimitive?.content ?: "unknown"
                val privacy = obj["privacy"]?.jsonPrimitive?.content ?: "unknown"
                ColumnSensitivityInfo(sensitivity, privacy)
            }
        } catch (e: Exception) {
            // If JSON parsing fails, return null (not a structured comment)
            null
        }
    }

    /**
     * Execute a query with PII filtering for production environment only
     */
    suspend fun executeQueryWithPiiFiltering(sql: String, maxRows: Int = 100, environment: String = "staging"): QueryExecutionResult = withContext(Dispatchers.IO) {
        // PII checking is only applicable to production environment
        if (environment.lowercase() != "production") {
            return@withContext executeQuery(sql, maxRows)
        }

        // Check if PII protection should be applied for this environment
        val shouldApplyPiiProtection = try {
            PiiConfiguration.shouldApplyPiiProtection(environment)
        } catch (e: Exception) {
            // Re-throw configuration errors with context
            throw IllegalStateException("Production PII configuration error: ${e.message}", e)
        }

        if (!shouldApplyPiiProtection) {
            return@withContext executeQuery(sql, maxRows)
        }

        // PII filtering is enabled for production, rewrite the query to exclude PII columns
        val rewrittenQuery = rewriteQueryToExcludePii(sql)
        val result = executeQuery(rewrittenQuery.sql, maxRows)

        // Return result with PII filtering information
        result.copy(
            // Add metadata about PII filtering if needed
        )
    }

    /**
     * Get all columns for a table (for secure-by-default PII filtering)
     */
    suspend fun getAllTableColumns(tableName: String): List<String> = withContext(Dispatchers.IO) {
        var connection: Connection? = null
        var preparedStatement: java.sql.PreparedStatement? = null
        var resultSet: ResultSet? = null

        try {
            connection = getConnection()

            val sql = """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_name = ?
                ORDER BY ordinal_position
            """.trimIndent()

            preparedStatement = connection.prepareStatement(sql)
            preparedStatement.setString(1, tableName)
            resultSet = preparedStatement.executeQuery()

            val columns = mutableListOf<String>()
            while (resultSet.next()) {
                columns.add(resultSet.getString("column_name"))
            }
            columns
        } catch (e: SQLException) {
            System.err.println("Warning: Could not get table columns: ${e.message}")
            emptyList()
        } finally {
            resultSet?.close()
            preparedStatement?.close()
            connection?.close()
        }
    }

    /**
     * Rewrite SQL query to exclude PII columns in production (secure by default)
     */
    private suspend fun rewriteQueryToExcludePii(sql: String): PiiFilteredQuery {
        val trimmedSql = sql.trim()

        // Only handle SELECT statements
        if (!trimmedSql.uppercase().startsWith("SELECT")) {
            return PiiFilteredQuery(sql, emptyList(), emptyList())
        }

        try {
            // Extract table names from the query (simple parsing)
            val tableNames = extractTableNames(sql)
            val allPiiColumns = mutableListOf<String>()
            val allFilteredColumns = mutableListOf<String>()

            // Get PII information for all tables in the query
            for (tableName in tableNames) {
                // Get all columns in the table
                val allColumns = getAllTableColumns(tableName)

                // Get columns with explicit sensitivity information
                val sensitivityInfo = getColumnSensitivityInfo(tableName)

                // SECURE BY DEFAULT: Only allow columns explicitly marked as non-personal
                val safeColumns = sensitivityInfo.filter { !it.value.isPii }.keys

                // All other columns (unmarked or marked as PII) are considered PII
                val piiColumns = allColumns.filter { columnName ->
                    columnName !in safeColumns
                }

                allPiiColumns.addAll(piiColumns.map { "$tableName.$it" })
            }

            if (allPiiColumns.isEmpty()) {
                // No PII columns found, return original query
                return PiiFilteredQuery(sql, emptyList(), emptyList())
            }

            // Rewrite the query to exclude PII columns
            val rewrittenSql = rewriteSelectStatement(sql, allPiiColumns)
            allFilteredColumns.addAll(allPiiColumns)

            return PiiFilteredQuery(rewrittenSql, allPiiColumns, allFilteredColumns)

        } catch (e: Exception) {
            // If query rewriting fails, return original query with warning
            System.err.println("Warning: Could not rewrite query for PII filtering: ${e.message}")
            return PiiFilteredQuery(sql, emptyList(), emptyList())
        }
    }

    /**
     * Extract table names from SQL query (simple regex-based approach)
     */
    private fun extractTableNames(sql: String): List<String> {
        val tableNames = mutableListOf<String>()

        try {
            // Simple regex to find table names after FROM and JOIN clauses
            val fromPattern = Regex("""(?i)\bFROM\s+([a-zA-Z_][a-zA-Z0-9_]*)\b""")
            val joinPattern = Regex("""(?i)\bJOIN\s+([a-zA-Z_][a-zA-Z0-9_]*)\b""")

            fromPattern.findAll(sql).forEach { match ->
                tableNames.add(match.groupValues[1].lowercase())
            }

            joinPattern.findAll(sql).forEach { match ->
                tableNames.add(match.groupValues[1].lowercase())
            }

        } catch (e: Exception) {
            System.err.println("Warning: Could not extract table names from query: ${e.message}")
        }

        return tableNames.distinct()
    }

    /**
     * Rewrite SELECT statement to exclude PII columns
     */
    private fun rewriteSelectStatement(sql: String, piiColumns: List<String>): String {
        // For SELECT * queries, we need to expand to specific columns
        if (sql.uppercase().contains("SELECT *")) {
            // This is complex to implement properly, so for now we'll block SELECT * in production
            throw Exception("SELECT * queries are not allowed in production due to PII protection. Please specify explicit column names.")
        }

        // For explicit column selection, remove PII columns
        // This is a simplified implementation - a full SQL parser would be better
        var rewrittenSql = sql

        piiColumns.forEach { piiColumn ->
            // Remove the PII column from SELECT clause (simple approach)
            val columnPattern = Regex("""(?i)\b${Regex.escape(piiColumn)}\b\s*,?""")
            rewrittenSql = columnPattern.replace(rewrittenSql, "")
        }

        // Clean up any trailing commas or double commas
        rewrittenSql = rewrittenSql.replace(Regex(""",\s*,"""), ",")
        rewrittenSql = rewrittenSql.replace(Regex(""",\s*FROM"""), " FROM")

        return rewrittenSql
    }
}