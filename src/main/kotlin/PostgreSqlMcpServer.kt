import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlinx.io.asSource
import kotlinx.io.asSink
import kotlinx.io.buffered

/**
 * PostgreSQL MCP Server
 *
 * This server provides tools to interact with PostgreSQL databases through the Model Context Protocol.
 * It supports querying, schema inspection, relationship discovery, and intelligent JOIN suggestions.
 */
fun main(args: Array<String>) {
    try {
        runMcpServer()
    } catch (e: Exception) {
        System.err.println("Failed to start server: ${e.message}")
        e.printStackTrace()
        kotlin.system.exitProcess(1)
    }
}



/**
 * Run MCP server using the official Kotlin MCP SDK
 */
private fun runMcpServer() {
    // Initialize HikariCP connection manager for environment-based routing
    val connectionManager = HikariConnectionManager()

    // Initialize all configured environments
    runBlocking {
        try {
            connectionManager.initializeEnvironments()
            System.err.println("Successfully initialized environment-based database connections")
            System.err.println("Available environments: ${connectionManager.getAvailableEnvironments().joinToString(", ")}")
        } catch (e: Exception) {
            System.err.println("Failed to initialize database connections: ${e.message}")
            System.err.println("Please check your database.properties configuration file")
            e.printStackTrace()
            kotlin.system.exitProcess(1)
        }
    }

    // Create the MCP Server instance
    val server = Server(
        serverInfo = Implementation(
            name = "postgres-mcp-tool",
            version = "1.0.0"
        ),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true)
            )
        )
    )

    // Add shutdown hook for proper cleanup
    Runtime.getRuntime().addShutdownHook(Thread {
        System.err.println("Shutting down PostgreSQL MCP Server...")
        connectionManager.shutdown()
    })

    // Register all PostgreSQL tools
    registerPostgreSqlTools(server, connectionManager)

    // Create STDIO transport and connect
    val transport = StdioServerTransport(
        inputStream = System.`in`.asSource().buffered(),
        outputStream = System.out.asSink().buffered()
    )

    System.err.println("PostgreSQL MCP Server started. Listening for requests...")

    runBlocking {
        server.connect(transport)
        val done = kotlinx.coroutines.Job()
        server.onClose {
            done.complete()
        }
        done.join()
    }
}

/**
 * Register all PostgreSQL tools with the MCP server
 */
private fun registerPostgreSqlTools(server: Server, connectionManager: HikariConnectionManager) {
    // Register postgres_query tool
    server.addTool(
        name = "postgres_query",
        description = "Execute a SELECT query against the PostgreSQL database",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                putJsonObject("sql") {
                    put("type", "string")
                    put("description", "The SELECT SQL query to execute")
                }
                putJsonObject("environment") {
                    put("type", "string")
                    put("description", "The database environment to query (staging, release, production). Defaults to staging if not specified.")
                    putJsonArray("enum") {
                        add("staging")
                        add("release")
                        add("production")
                    }
                }
            },
            required = listOf("sql")
        )
    ) { request ->
        val sql = request.arguments["sql"]?.jsonPrimitive?.content
        val environment = request.arguments["environment"]?.jsonPrimitive?.content

        val result = if (sql != null && sql.trim().uppercase().startsWith("SELECT")) {
            try {
                val database = connectionManager.getConnection(environment)
                val queryResult = database.executeQuery(sql, 100)
                val envInfo = if (environment != null) " (Environment: $environment)" else ""
                "Query executed successfully!$envInfo\nRows returned: ${queryResult.rowCount}\n\n" +
                        formatQueryResult(queryResult)
            } catch (e: Exception) {
                "Error executing query: ${e.message}"
            }
        } else {
            "Only SELECT queries are allowed for safety"
        }

        CallToolResult(content = listOf(TextContent(result)))
    }

    // Register postgres_list_tables tool
    server.addTool(
        name = "postgres_list_tables",
        description = "List all tables in the PostgreSQL database",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                putJsonObject("environment") {
                    put("type", "string")
                    put("description", "The database environment to query (staging, release, production). Defaults to staging if not specified.")
                    putJsonArray("enum") {
                        add("staging")
                        add("release")
                        add("production")
                    }
                }
            }
        )
    ) { request ->
        val environment = request.arguments["environment"]?.jsonPrimitive?.content

        val result = try {
            val database = connectionManager.getConnection(environment)
            val tables = database.listTables()
            val envInfo = if (environment != null) " (Environment: $environment)" else ""

            if (tables.isEmpty()) {
                "No tables found in the database$envInfo."
            } else {
                "Tables in database$envInfo:\n" + tables.joinToString("\n") { "• ${it.name}" }
            }
        } catch (e: Exception) {
            "Error listing tables: ${e.message}"
        }

        CallToolResult(content = listOf(TextContent(result)))
    }
    // Register postgres_get_relationships tool
    server.addTool(
        name = "postgres_get_relationships",
        description = "Get table relationships including foreign keys, primary keys, and constraints",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                putJsonObject("table_name") {
                    put("type", "string")
                    put("description", "Name of the table to get relationships for")
                }
                putJsonObject("environment") {
                    put("type", "string")
                    put("description", "The database environment to query (staging, release, production). Defaults to staging if not specified.")
                    putJsonArray("enum") {
                        add("staging")
                        add("release")
                        add("production")
                    }
                }
            },
            required = listOf("table_name")
        )
    ) { request ->
        val tableName = request.arguments["table_name"]?.jsonPrimitive?.content
        val environment = request.arguments["environment"]?.jsonPrimitive?.content

        val result = if (tableName != null) {
            try {
                val database = connectionManager.getConnection(environment)
                val relationships = database.getTableRelationships(tableName)
                val envInfo = if (environment != null) " (Environment: $environment)" else ""
                formatTableRelationships(relationships, envInfo)
            } catch (e: Exception) {
                "Error getting relationships for table '$tableName': ${e.message}"
            }
        } else {
            "Table name is required"
        }

        CallToolResult(content = listOf(TextContent(result)))
    }

    // Register postgres_get_table_schema tool
    server.addTool(
        name = "postgres_get_table_schema",
        description = "Get detailed schema information for a table including columns and relationships",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                putJsonObject("table_name") {
                    put("type", "string")
                    put("description", "Name of the table to get schema for")
                }
                putJsonObject("environment") {
                    put("type", "string")
                    put("description", "The database environment to query (staging, release, production). Defaults to staging if not specified.")
                    putJsonArray("enum") {
                        add("staging")
                        add("release")
                        add("production")
                    }
                }
            },
            required = listOf("table_name")
        )
    ) { request ->
        val tableName = request.arguments["table_name"]?.jsonPrimitive?.content
        val environment = request.arguments["environment"]?.jsonPrimitive?.content

        val result = if (tableName != null) {
            try {
                val database = connectionManager.getConnection(environment)
                val schema = database.getTableSchema(tableName)
                val relationships = database.getTableRelationships(tableName)
                val envInfo = if (environment != null) " (Environment: $environment)" else ""
                formatTableSchemaWithRelationships(tableName, schema, relationships, envInfo)
            } catch (e: Exception) {
                "Error getting schema for table '$tableName': ${e.message}"
            }
        } else {
            "Table name is required"
        }

        CallToolResult(content = listOf(TextContent(result)))
    }

    // Register postgres_suggest_joins tool
    server.addTool(
        name = "postgres_suggest_joins",
        description = "Suggest possible JOIN queries based on foreign key relationships",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                putJsonObject("table_name") {
                    put("type", "string")
                    put("description", "Name of the table to suggest joins for")
                }
                putJsonObject("environment") {
                    put("type", "string")
                    put("description", "The database environment to query (staging, release, production). Defaults to staging if not specified.")
                    putJsonArray("enum") {
                        add("staging")
                        add("release")
                        add("production")
                    }
                }
            },
            required = listOf("table_name")
        )
    ) { request ->
        val tableName = request.arguments["table_name"]?.jsonPrimitive?.content
        val environment = request.arguments["environment"]?.jsonPrimitive?.content

        val result = if (tableName != null) {
            try {
                val database = connectionManager.getConnection(environment)
                val suggestions = database.getJoinSuggestions(tableName)
                val envInfo = if (environment != null) " (Environment: $environment)" else ""
                formatJoinSuggestions(tableName, suggestions, envInfo)
            } catch (e: Exception) {
                "Error getting join suggestions for table '$tableName': ${e.message}"
            }
        } else {
            "Table name is required"
        }

        CallToolResult(content = listOf(TextContent(result)))
    }

    // Register postgres_get_database_info tool
    server.addTool(
        name = "postgres_get_database_info",
        description = "Get database name and connection information",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                putJsonObject("environment") {
                    put("type", "string")
                    put("description", "The database environment to query (staging, release, production). Defaults to staging if not specified.")
                    putJsonArray("enum") {
                        add("staging")
                        add("release")
                        add("production")
                    }
                }
            }
        )
    ) { request ->
        val result = try {
            val environment = request.arguments["environment"]?.jsonPrimitive?.content
            val repository = connectionManager.getConnection(environment)
            val dbInfo = repository.getDatabaseInfo()
            formatDatabaseInfo(dbInfo, environment ?: "staging")
        } catch (e: Exception) {
            "Error getting database info: ${e.message}"
        }

        CallToolResult(content = listOf(TextContent(result)))
    }

    // Register postgres_connection_stats tool
    server.addTool(
        name = "postgres_connection_stats",
        description = "Get connection pool statistics and health information",
        inputSchema = Tool.Input(
            properties = buildJsonObject {}
        )
    ) { request ->
        val result = try {
            val stats = connectionManager.getConnectionStats()
            formatConnectionStats(stats)
        } catch (e: Exception) {
            "Error getting connection statistics: ${e.message}"
        }

        CallToolResult(content = listOf(TextContent(result)))
    }
}




/**
 * Format query results as a simple table
 */
private fun formatQueryResult(result: QueryExecutionResult): String {
    if (result.rows.isEmpty()) {
        return "No rows returned."
    }

    val columnNames = result.columns.map { it.name }
    val columnWidths = columnNames.map { name ->
        maxOf(name.length, result.rows.maxOfOrNull { row ->
            row[name]?.toString()?.length ?: 4
        } ?: 4)
    }

    return buildString {
        // Header
        appendLine(columnNames.zip(columnWidths) { name, width ->
            name.padEnd(width)
        }.joinToString(" | "))

        // Separator
        appendLine(columnWidths.map { "-".repeat(it) }.joinToString("-+-"))

        // Rows
        result.rows.forEach { row ->
            appendLine(columnNames.zip(columnWidths) { name, width ->
                (row[name]?.toString() ?: "NULL").padEnd(width)
            }.joinToString(" | "))
        }
    }
}

/**
 * Format table relationships information
 */
private fun formatTableRelationships(relationships: TableRelationshipSummary, envInfo: String = ""): String {
    return buildString {
        appendLine("=== Table Relationships for '${relationships.tableName}'$envInfo ===")
        appendLine()

        // Primary Keys
        if (relationships.primaryKeys.isNotEmpty()) {
            appendLine("PRIMARY KEYS:")
            relationships.primaryKeys.forEach { pk ->
                appendLine("  • ${pk.columnName} (constraint: ${pk.constraintName})")
            }
            appendLine()
        }

        // Foreign Keys (outgoing relationships)
        if (relationships.foreignKeys.isNotEmpty()) {
            appendLine("FOREIGN KEYS (references other tables):")
            relationships.foreignKeys.forEach { fk ->
                val actions = listOfNotNull(
                    fk.onDelete?.let { "ON DELETE $it" },
                    fk.onUpdate?.let { "ON UPDATE $it" }
                ).joinToString(", ")
                appendLine("  • ${fk.sourceColumn} → ${fk.targetTable}.${fk.targetColumn}")
                if (actions.isNotEmpty()) {
                    appendLine("    ($actions)")
                }
            }
            appendLine()
        }

        // Referenced By (incoming relationships)
        if (relationships.referencedBy.isNotEmpty()) {
            appendLine("REFERENCED BY (other tables reference this table):")
            relationships.referencedBy.forEach { ref ->
                appendLine("  • ${ref.sourceTable}.${ref.sourceColumn} → ${ref.targetColumn}")
            }
            appendLine()
        }

        // Unique Constraints
        if (relationships.uniqueConstraints.isNotEmpty()) {
            appendLine("UNIQUE CONSTRAINTS:")
            relationships.uniqueConstraints.groupBy { it.constraintName }.forEach { (constraintName, columns) ->
                val columnList = columns.map { it.columnName }.joinToString(", ")
                appendLine("  • $constraintName: ($columnList)")
            }
            appendLine()
        }

        if (relationships.primaryKeys.isEmpty() &&
            relationships.foreignKeys.isEmpty() &&
            relationships.referencedBy.isEmpty() &&
            relationships.uniqueConstraints.isEmpty()) {
            appendLine("No relationships found for this table.")
        }
    }
}

/**
 * Format table schema with relationship information
 */
private fun formatTableSchemaWithRelationships(tableName: String, schema: List<TableColumn>, relationships: TableRelationshipSummary, envInfo: String = ""): String {
    return buildString {
        appendLine("=== Schema for table '$tableName'$envInfo ===")
        appendLine()

        // Column information
        appendLine("COLUMNS:")
        if (schema.isEmpty()) {
            appendLine("  No columns found.")
        } else {
            val maxNameWidth = schema.maxOfOrNull { it.name.length } ?: 10
            val maxTypeWidth = schema.maxOfOrNull { it.type.length } ?: 10

            schema.forEach { column ->
                val pkIndicator = if (relationships.primaryKeys.any { it.columnName == column.name }) " (PK)" else ""
                val fkIndicator = if (relationships.foreignKeys.any { it.sourceColumn == column.name }) " (FK)" else ""
                val uniqueIndicator = if (relationships.uniqueConstraints.any { it.columnName == column.name }) " (UNIQUE)" else ""
                val nullableIndicator = if (column.nullable) " NULL" else " NOT NULL"

                val name = column.name.padEnd(maxNameWidth)
                val type = column.type.padEnd(maxTypeWidth)
                val sizeInfo = column.size?.let { " ($it)" } ?: ""
                val defaultInfo = column.defaultValue?.let { " DEFAULT $it" } ?: ""

                appendLine("  $name $type$sizeInfo$nullableIndicator$pkIndicator$fkIndicator$uniqueIndicator$defaultInfo")
            }
        }
        appendLine()

        // Add relationship summary
        val relationshipSummary = formatTableRelationships(relationships, envInfo)
        append(relationshipSummary)
    }
}

/**
 * Format JOIN suggestions
 */
private fun formatJoinSuggestions(tableName: String, suggestions: List<JoinRecommendation>, envInfo: String = ""): String {
    return buildString {
        appendLine("=== JOIN Suggestions for table '$tableName'$envInfo ===")
        appendLine()

        if (suggestions.isEmpty()) {
            appendLine("No JOIN suggestions available. This table has no foreign key relationships.")
        } else {
            appendLine("Based on foreign key relationships, you can JOIN with:")
            appendLine()

            suggestions.forEach { suggestion ->
                appendLine("${suggestion.joinType} ${suggestion.toTable}")
                appendLine("  ON ${suggestion.joinCondition}")
                appendLine()
            }

            appendLine("Example query:")
            val firstSuggestion = suggestions.first()
            appendLine("SELECT *")
            appendLine("FROM $tableName")
            suggestions.forEach { suggestion ->
                appendLine("${suggestion.joinType} ${suggestion.toTable}")
                appendLine("  ON ${suggestion.joinCondition}")
            }
        }
    }
}

/**
 * Format HikariCP connection statistics for monitoring
 */
private fun formatConnectionStats(stats: Map<String, Any>): String {
    return buildString {
        appendLine("=== HikariCP Connection Pool Statistics ===")
        appendLine()

        // Overall summary
        val summary = stats["summary"] as? Map<*, *>
        if (summary != null) {
            appendLine("Overall Pool Summary:")
            appendLine("  • Total Active Connections: ${summary["total_active_connections"]}")
            appendLine("  • Total Idle Connections: ${summary["total_idle_connections"]}")
            appendLine("  • Total Maximum Connections: ${summary["total_maximum_connections"]}")
            appendLine("  • Pool Utilization: ${summary["connection_utilization_percent"]}%")
            appendLine()
        }

        appendLine("Environment Status:")
        appendLine("  • Total Environments: ${stats["total_environments"]}")
        appendLine("  • Environment Mode: ${stats["environment_mode"]}")

        val environments = stats["available_environments"] as? List<*>
        if (environments != null && environments.isNotEmpty()) {
            appendLine("  • Available: ${environments.joinToString(", ")}")
        }
        appendLine()

        // Per-environment pool details
        val poolDetails = stats["pool_details"] as? Map<*, *>
        if (poolDetails != null && poolDetails.isNotEmpty()) {
            appendLine("HikariCP Pool Details:")
            poolDetails.forEach { (env, details) ->
                val detailMap = details as? Map<*, *>
                if (detailMap != null) {
                    appendLine("  Environment: $env")
                    appendLine("    Pool Name: ${detailMap["pool_name"]}")
                    appendLine("    Active Connections: ${detailMap["active_connections"]}")
                    appendLine("    Idle Connections: ${detailMap["idle_connections"]}")
                    appendLine("    Total Connections: ${detailMap["total_connections"]}")
                    appendLine("    Threads Awaiting: ${detailMap["threads_awaiting_connection"]}")
                    appendLine("    Maximum Pool Size: ${detailMap["maximum_pool_size"]}")
                    appendLine("    Minimum Idle: ${detailMap["minimum_idle"]}")
                    appendLine("    Connection Timeout: ${detailMap["connection_timeout"]}ms")
                    appendLine("    Idle Timeout: ${detailMap["idle_timeout"]}ms")
                    appendLine("    Max Lifetime: ${detailMap["max_lifetime"]}ms")
                    appendLine("    Last Used: ${detailMap["last_used"]}")
                    appendLine("    Status: ${if (detailMap["is_closed"] == true) "✗ Closed" else "✓ Active"}")
                    appendLine()
                }
            }
        }

        appendLine("HikariCP Features:")
        appendLine("  • Enterprise-grade connection pooling")
        appendLine("  • Automatic connection validation")
        appendLine("  • Connection leak detection")
        appendLine("  • JMX monitoring and metrics")
        appendLine("  • Optimized performance and memory usage")
        appendLine("  • Thread-safe concurrent operations")
    }
}

/**
 * Format database information
 */
private fun formatDatabaseInfo(dbInfo: DatabaseInfo, environment: String): String {
    return buildString {
        appendLine("=== Database Information ===")
        appendLine()
        appendLine("Database Details:")
        appendLine("  • Database Name: ${dbInfo.databaseName}")
        appendLine("  • Database Product: ${dbInfo.databaseProduct}")
        appendLine("  • Database Version: ${dbInfo.databaseVersion}")
        appendLine()
        appendLine("Connection Details:")
        appendLine("  • Environment: $environment")
        appendLine("  • Connection URL: ${dbInfo.url}")
        appendLine("  • Username: ${dbInfo.username}")
        appendLine()
        appendLine("Driver Information:")
        appendLine("  • Driver Name: ${dbInfo.driverName}")
        appendLine("  • Driver Version: ${dbInfo.driverVersion}")
    }
}
