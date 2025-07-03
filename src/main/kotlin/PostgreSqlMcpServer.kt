@file:OptIn(ExperimentalSerializationApi::class)

import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import model.Environment
import model.connection.ReconnectionResult
import model.database.TableColumn
import model.query.QueryExecutionResult
import model.relationship.JoinRecommendation
import model.relationship.TableRelationshipSummary
import model.security.ColumnSensitivityInfo
import model.security.PiiConfiguration

// Reusable JSON instances to avoid creating new instances for each usage
private val jsonParser = Json { ignoreUnknownKeys = true }
private val prettyJsonFormatter = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    ignoreUnknownKeys = true
}

/**
 * Utility function to add environment enum array to JSON object builder
 */
private fun kotlinx.serialization.json.JsonObjectBuilder.addEnvironmentEnumArray() {
    putJsonArray("enum") {
        Environment.getSupportedEnvironments().forEach { add(it) }
    }
}

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
            System.err.println(
                "Available environments: ${
                    connectionManager.getAvailableEnvironments().joinToString(", ")
                }"
            )
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
                    put(
                        "description",
                        "The database environment to query (${
                            Environment.getSupportedEnvironments().joinToString(", ")
                        }). Defaults to ${Environment.getDefault().value} if not specified."
                    )
                    addEnvironmentEnumArray()
                }
            },
            required = listOf("sql")
        )
    ) { request ->
        val sql = request.arguments["sql"]?.jsonPrimitive?.content
        val environment = try {
            request.arguments["environment"]?.jsonPrimitive?.content
                ?.let { Environment.fromString(it) } ?: Environment.getDefault()
        } catch (e: IllegalArgumentException) {
            return@addTool CallToolResult(content = listOf(TextContent("Invalid environment: ${e.message}")))
        }

        val result = if (sql != null && sql.trim().uppercase().startsWith("SELECT")) {
            try {
                val database = connectionManager.getConnection(environment)

                // Check if PII protection should be applied for this environment
                val shouldApplyPiiProtection = try {
                    PiiConfiguration.shouldApplyPiiProtection(environment.value)
                } catch (e: Exception) {
                    throw IllegalStateException("Production PII configuration error: ${e.message}", e)
                }

                // Use PII filtering if protection is enabled for this environment
                val queryResult = if (shouldApplyPiiProtection) {
                    database.executeQueryWithPiiFiltering(sql, 100, environment)
                } else {
                    database.executeQuery(sql, 100)
                }

                val piiWarning = if (shouldApplyPiiProtection) {
                    "\n🔒 PRODUCTION PII PROTECTION ENABLED - Sensitive columns automatically filtered\n"
                } else ""

                "Query executed successfully! (Environment: ${environment.value})$piiWarning\nRows returned: ${queryResult.rowCount}\n\n" +
                        formatQueryResult(queryResult, environment)
            } catch (e: Exception) {
                if (environment == Environment.PRODUCTION && e.message?.contains("SELECT *") == true) {
                    "❌ Production Safety Error: ${e.message}\n\n" +
                            "💡 Tip: In production, you must specify explicit column names to avoid accidentally querying PII data.\n" +
                            "Example: SELECT id, name, created_at FROM users WHERE ..."
                } else {
                    "Error executing query: ${e.message}"
                }
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
                    put(
                        "description",
                        "The database environment to query (${
                            Environment.getSupportedEnvironments().joinToString(", ")
                        }). Defaults to ${Environment.getDefault().value} if not specified."
                    )
                    addEnvironmentEnumArray()
                }
            }
        )
    ) { request ->
        val environment = try {
            request.arguments["environment"]?.jsonPrimitive?.content
                ?.let { Environment.fromString(it) } ?: Environment.getDefault()
        } catch (e: IllegalArgumentException) {
            return@addTool CallToolResult(content = listOf(TextContent("Invalid environment: ${e.message}")))
        }

        val result = try {
            val database = connectionManager.getConnection(environment)
            val tables = database.listTables()

            if (tables.isEmpty()) {
                "No tables found in the database (Environment: ${environment.value})."
            } else {
                "Tables in database (Environment: ${environment.value}):\n" + tables.joinToString("\n") { "• ${it.name}" }
            }
        } catch (e: Exception) {
            "Error listing tables: ${e.message}"
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
                    put(
                        "description",
                        "The database environment to query (${
                            Environment.getSupportedEnvironments().joinToString(", ")
                        }). Defaults to ${Environment.getDefault().value} if not specified."
                    )
                    addEnvironmentEnumArray()
                }
            },
            required = listOf("table_name")
        )
    ) { request ->
        val tableName = request.arguments["table_name"]?.jsonPrimitive?.content
        val environment = try {
            request.arguments["environment"]?.jsonPrimitive?.content
                ?.let { Environment.fromString(it) } ?: Environment.getDefault()
        } catch (e: IllegalArgumentException) {
            return@addTool CallToolResult(content = listOf(TextContent("Invalid environment: ${e.message}")))
        }

        val result = if (tableName != null) {
            try {
                val database = connectionManager.getConnection(environment)
                val schema = database.getTableSchema(tableName)
                val relationships = database.getTableRelationships(tableName)

                // Use enhanced formatting for production, standard for others
                if (environment == Environment.PRODUCTION) {
                    val sensitivityInfo = database.getColumnSensitivityInfo(tableName)
                    formatEnhancedTableSchemaForProduction(tableName, schema, relationships, sensitivityInfo, environment)
                } else {
                    formatTableSchemaWithRelationships(tableName, schema, relationships, environment)
                }
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
                    put(
                        "description",
                        "The database environment to query (${
                            Environment.getSupportedEnvironments().joinToString(", ")
                        }). Defaults to ${Environment.getDefault().value} if not specified."
                    )
                    addEnvironmentEnumArray()
                }
            },
            required = listOf("table_name")
        )
    ) { request ->
        val tableName = request.arguments["table_name"]?.jsonPrimitive?.content
        val environment = try {
            request.arguments["environment"]?.jsonPrimitive?.content
                ?.let { Environment.fromString(it) } ?: Environment.getDefault()
        } catch (e: IllegalArgumentException) {
            return@addTool CallToolResult(content = listOf(TextContent("Invalid environment: ${e.message}")))
        }

        val result = if (tableName != null) {
            try {
                val database = connectionManager.getConnection(environment)
                val suggestions = database.getJoinSuggestions(tableName)
                formatJoinSuggestions(tableName, suggestions, environment)
            } catch (e: Exception) {
                "Error getting join suggestions for table '$tableName': ${e.message}"
            }
        } else {
            "Table name is required"
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



    // Register postgres_explain_query tool
    server.addTool(
        name = "postgres_explain_query",
        description = "Get PostgreSQL query execution plan with performance analysis (EXPLAIN ANALYZE)",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                putJsonObject("sql") {
                    put("type", "string")
                    put("description", "The SELECT SQL query to analyze")
                }
                putJsonObject("environment") {
                    put("type", "string")
                    put(
                        "description",
                        "The database environment to query (${
                            Environment.getSupportedEnvironments().joinToString(", ")
                        }). Defaults to ${Environment.getDefault().value} if not specified."
                    )
                    addEnvironmentEnumArray()
                }
            },
            required = listOf("sql")
        )
    ) { request ->
        val sql = request.arguments["sql"]?.jsonPrimitive?.content
        val environment = try {
            request.arguments["environment"]?.jsonPrimitive?.content
                ?.let { Environment.fromString(it) } ?: Environment.getDefault()
        } catch (e: IllegalArgumentException) {
            return@addTool CallToolResult(content = listOf(TextContent("Invalid environment: ${e.message}")))
        }

        val result = if (sql != null && sql.trim().uppercase().startsWith("SELECT")) {
            try {
                val database = connectionManager.getConnection(environment)
                val explainResult = database.explainQuery(sql)

                "Query execution plan generated successfully! (Environment: ${environment.value})\n\n" +
                        formatExplainResult(explainResult, sql)
            } catch (e: Exception) {
                "Error analyzing query: ${e.message}"
            }
        } else {
            "Only SELECT queries are allowed for EXPLAIN ANALYZE"
        }

        CallToolResult(content = listOf(TextContent(result)))
    }

    // Register postgres_reconnect_database tool
    server.addTool(
        name = "postgres_reconnect_database",
        description = "Reconnect to PostgreSQL database(s) - useful when VPN connection is restored or network issues are resolved",
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                putJsonObject("environment") {
                    put("type", "string")
                    put(
                        "description",
                        "The database environment to reconnect (${
                            Environment.getSupportedEnvironments().joinToString(", ")
                        }). If not specified, reconnects to all environments."
                    )
                    addEnvironmentEnumArray()
                }
                putJsonObject("test_connection") {
                    put("type", "boolean")
                    put(
                        "description",
                        "Whether to test connection health before attempting reconnection. Defaults to true."
                    )
                }
            }
        )
    ) { request ->
        val environment = try {
            request.arguments["environment"]?.jsonPrimitive?.content
                ?.let { Environment.fromString(it) }
        } catch (e: IllegalArgumentException) {
            return@addTool CallToolResult(content = listOf(TextContent("Invalid environment: ${e.message}")))
        }

        val testConnection = try {
            request.arguments["test_connection"]?.jsonPrimitive?.content?.toBoolean() ?: true
        } catch (e: Exception) {
            true
        }

        val result = try {
            runBlocking {
                if (environment != null) {
                    // Reconnect specific environment
                    if (testConnection) {
                        val isHealthy = connectionManager.testEnvironmentConnection(environment)
                        if (isHealthy) {
                            "✓ Connection to ${environment.value} database is already healthy. No reconnection needed."
                        } else {
                            val reconnectionResult = connectionManager.reconnectEnvironment(environment)
                            formatReconnectionResult(reconnectionResult)
                        }
                    } else {
                        val reconnectionResult = connectionManager.reconnectEnvironment(environment)
                        formatReconnectionResult(reconnectionResult)
                    }
                } else {
                    // Reconnect all environments
                    if (testConnection) {
                        val healthStatus = connectionManager.getConnectionHealth()
                        val unhealthyEnvironments = healthStatus.filter { !it.value }.keys

                        if (unhealthyEnvironments.isEmpty()) {
                            "✓ All database connections are healthy. No reconnection needed.\n\n" +
                                    "Connection Status:\n" + healthStatus.map { (env, healthy) ->
                                "• $env: ${if (healthy) "✓ Healthy" else "✗ Unhealthy"}"
                            }.joinToString("\n")
                        } else {
                            val reconnectionResults = connectionManager.reconnectAllEnvironments()
                            formatMultipleReconnectionResults(reconnectionResults, healthStatus)
                        }
                    } else {
                        val reconnectionResults = connectionManager.reconnectAllEnvironments()
                        formatMultipleReconnectionResults(reconnectionResults)
                    }
                }
            }
        } catch (e: Exception) {
            "Error during database reconnection: ${e.message}"
        }

        CallToolResult(content = listOf(TextContent(result)))
    }
}


/**
 * Format query results as a simple table with environment-aware PII protection
 */
private fun formatQueryResult(
    result: QueryExecutionResult,
    environment: Environment = Environment.getDefault()
): String {
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
        // Check if PII protection should be applied for this environment
        val shouldApplyPiiProtection = try {
            PiiConfiguration.shouldApplyPiiProtection(environment.value)
        } catch (e: Exception) {
            false // If configuration is missing, assume disabled
        }

        // Add PII protection warning if applicable
        if (shouldApplyPiiProtection) {
            appendLine("🔒 PRODUCTION PII PROTECTION ACTIVE - Columns marked as 'personal' have been excluded")
            appendLine()
        }

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

        // Add PII protection footer if applicable
        if (shouldApplyPiiProtection) {
            appendLine()
            appendLine("🛡️  PRODUCTION PII PROTECTION Security Notice:")
            appendLine("   • Columns marked as 'personal' have been automatically excluded")
            appendLine("   • Only columns marked as 'non-personal' are included in results")
            appendLine("   • PII checking is enabled for production environment")
        }
    }
}

/**
 * Format table relationships information
 */
private fun formatTableRelationships(relationships: TableRelationshipSummary, environment: Environment = Environment.getDefault()): String {
    return buildString {
        appendLine("=== Table Relationships for '${relationships.tableName}' (Environment: ${environment.value}) ===")
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
            relationships.uniqueConstraints.isEmpty()
        ) {
            appendLine("No relationships found for this table.")
        }
    }
}

/**
 * Format table schema with relationship information
 */
private fun formatTableSchemaWithRelationships(
    tableName: String,
    schema: List<TableColumn>,
    relationships: TableRelationshipSummary,
    environment: Environment = Environment.getDefault()
): String {
    return buildString {
        appendLine("=== Schema for table '$tableName' (Environment: ${environment.value}) ===")
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
                val uniqueIndicator =
                    if (relationships.uniqueConstraints.any { it.columnName == column.name }) " (UNIQUE)" else ""
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
        val relationshipSummary = formatTableRelationships(relationships, environment)
        append(relationshipSummary)
    }
}

/**
 * Format enhanced table schema with accessibility information for production environment
 */
private fun formatEnhancedTableSchemaForProduction(
    tableName: String,
    schema: List<TableColumn>,
    relationships: TableRelationshipSummary,
    sensitivityInfo: Map<String, ColumnSensitivityInfo>,
    environment: Environment = Environment.getDefault()
): String {
    return buildString {
        appendLine("=== Enhanced Table Analysis for '$tableName' (Environment: ${environment.value}) ===")
        appendLine()

        // Check if PII protection is active
        val shouldApplyPiiProtection = try {
            PiiConfiguration.shouldApplyPiiProtection(Environment.PRODUCTION.value)
        } catch (e: Exception) {
            false
        }

        if (shouldApplyPiiProtection) {
            appendLine("PRODUCTION PII PROTECTION ACTIVE")
            appendLine()
        }

        appendLine("COMPLETE TABLE STRUCTURE:")
        appendLine()

        // Column information with accessibility status
        appendLine("COLUMNS WITH ACCESSIBILITY STATUS:")
        if (schema.isEmpty()) {
            appendLine("  No columns found.")
        } else {
            val maxNameWidth = schema.maxOfOrNull { it.name.length } ?: 10
            val maxTypeWidth = schema.maxOfOrNull { it.type.length } ?: 10
            val maxConstraintWidth = 35 // Fixed width for constraint info

            var accessibleCount = 0
            var filteredCount = 0
            var unknownCount = 0
            val accessibleColumns = mutableListOf<String>()

            schema.forEach { column ->
                val pkIndicator = if (relationships.primaryKeys.any { it.columnName == column.name }) " (PK)" else ""
                val fkIndicator = if (relationships.foreignKeys.any { it.sourceColumn == column.name }) " (FK)" else ""
                val uniqueIndicator =
                    if (relationships.uniqueConstraints.any { it.columnName == column.name }) " (UNIQUE)" else ""
                val nullableIndicator = if (column.nullable) " NULL" else " NOT NULL"
                val sizeInfo = column.size?.let { " ($it)" } ?: ""
                val defaultInfo = column.defaultValue?.let { " DEFAULT $it" } ?: ""

                val name = column.name.padEnd(maxNameWidth)
                val type = column.type.padEnd(maxTypeWidth)
                val constraintInfo = "$sizeInfo$nullableIndicator$pkIndicator$fkIndicator$uniqueIndicator$defaultInfo"
                val paddedConstraintInfo = constraintInfo.padEnd(maxConstraintWidth)

                // Determine accessibility status
                val accessibilityStatus = if (shouldApplyPiiProtection) {
                    val sensitivity = sensitivityInfo[column.name]
                    when {
                        sensitivity != null && sensitivity.isPii -> {
                            filteredCount++
                            "FILTERED"
                        }
                        sensitivity != null && !sensitivity.isPii -> {
                            accessibleCount++
                            accessibleColumns.add(column.name)
                            "ACCESSIBLE"
                        }
                        else -> {
                            unknownCount++
                            "UNKNOWN"
                        }
                    }
                } else {
                    accessibleCount++
                    accessibleColumns.add(column.name)
                    "ACCESSIBLE"
                }

                appendLine("  $name $type $paddedConstraintInfo $accessibilityStatus")
            }

            appendLine()

            // Accessibility Summary
            appendLine("ACCESSIBILITY SUMMARY:")
            appendLine("  • Total columns in table: ${schema.size}")
            appendLine("  • Accessible columns: $accessibleCount (${(accessibleCount * 100 / schema.size)}%)")
            if (filteredCount > 0) {
                appendLine("  • Filtered columns: $filteredCount (${(filteredCount * 100 / schema.size)}%)")
            }
            if (unknownCount > 0) {
                appendLine("  • Unknown access columns: $unknownCount (${(unknownCount * 100 / schema.size)}%)")
            }

            // PII information if available
            if (sensitivityInfo.isNotEmpty()) {
                val nonPiiColumns = sensitivityInfo.filter { !it.value.isPii }
                val piiColumns = sensitivityInfo.filter { it.value.isPii }
                appendLine("  • Confirmed non-PII columns: ${nonPiiColumns.size}")
                if (piiColumns.isNotEmpty()) {
                    appendLine("  • Confirmed PII columns: ${piiColumns.size} (filtered from queries)")
                }
            }
            appendLine()
        }

        // Add relationship information (same as standard format)
        val relationshipSummary = formatTableRelationships(relationships, environment)
        append(relationshipSummary)

        // Add production-specific guidance if PII protection is active
        if (shouldApplyPiiProtection && schema.isNotEmpty()) {
            appendLine()
            appendLine("PRODUCTION ANALYSIS LIMITATIONS:")
            appendLine("  • This analysis combines table metadata with accessible privacy information")
            appendLine("  • PII columns are filtered from query results but visible in table structure")
            appendLine("  • Columns without privacy info have unknown accessibility status")
            appendLine()

            appendLine("AI QUERY SAFETY GUIDANCE:")
            appendLine("  • NEVER use SELECT * in production")

            val accessibleColumns = mutableListOf<String>()
            schema.forEach { column ->
                val sensitivity = sensitivityInfo[column.name]
                if (sensitivity != null && !sensitivity.isPii) {
                    accessibleColumns.add(column.name)
                }
            }

            if (accessibleColumns.isNotEmpty()) {
                appendLine("  • Only query confirmed accessible columns: ${accessibleColumns.joinToString(", ")}")
                appendLine("  • Avoid columns marked as FILTERED or UNKNOWN")
                appendLine("  • Use explicit column names: SELECT ${accessibleColumns.joinToString(", ")} FROM $tableName")
                appendLine()
                appendLine("SAFE QUERY EXAMPLE:")
                appendLine("  SELECT ${accessibleColumns.take(3).joinToString(", ")}")
                appendLine("  FROM $tableName")
                appendLine("  WHERE ${accessibleColumns.firstOrNull() ?: "id"} IS NOT NULL")
            } else {
                appendLine("  • DO NOT assume any columns are safe to query")
                appendLine("  • Contact database administrator to add privacy information")
            }
        }
    }
}

/**
 * Format JOIN suggestions
 */
private fun formatJoinSuggestions(
    tableName: String,
    suggestions: List<JoinRecommendation>,
    environment: Environment = Environment.getDefault()
): String {
    return buildString {
        appendLine("=== JOIN Suggestions for table '$tableName' (Environment: ${environment.value}) ===")
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

        val status = stats["status"] as? String

        if (status == "no_connections") {
            appendLine("🔄 Connection Status: No Active Connections")
            appendLine()
            appendLine("${stats["message"]}")
            appendLine()
            appendLine("🔧 To establish connections:")
            appendLine("  • Use: postgres_reconnect_database (reconnects all environments)")
            appendLine("  • Use: postgres_reconnect_database with environment parameter (reconnects specific environment)")
            appendLine()
            appendLine("💡 Common scenarios:")
            appendLine("  • VPN connection was lost and restored")
            appendLine("  • Database servers were temporarily unavailable")
            appendLine("  • MCP server started without valid connections")
            appendLine("  • Network connectivity issues occurred")
            appendLine()
        } else {
            appendLine("✅ Connection Status: Active Connections Available")
            appendLine()
        }

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
        } else {
            appendLine("  • Available: None (use postgres_reconnect_database to establish connections)")
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

        if (status != "no_connections") {
            appendLine("HikariCP Features:")
            appendLine("  • Enterprise-grade connection pooling")
            appendLine("  • Automatic connection validation")
            appendLine("  • Connection leak detection")
            appendLine("  • JMX monitoring and metrics")
            appendLine("  • Optimized performance and memory usage")
            appendLine("  • Thread-safe concurrent operations")
        }
    }
}




/**
 * Format EXPLAIN ANALYZE result for better readability using kotlinx.serialization
 */
private fun formatExplainResult(explainJson: String, originalQuery: String): String {
    return buildString {
        appendLine("=== QUERY EXECUTION PLAN ===")
        appendLine()
        appendLine("Original Query:")
        appendLine(originalQuery.trim())
        appendLine()
        appendLine("📊 Execution Plan:")
        appendLine("```json")

        // Use kotlinx.serialization to properly format JSON
        try {
            val jsonElement = jsonParser.parseToJsonElement(explainJson)
            val prettyJson = prettyJsonFormatter.encodeToString(JsonElement.serializer(), jsonElement)
            appendLine(prettyJson)
        } catch (e: Exception) {
            // If JSON parsing fails, show raw output
            appendLine(explainJson)
        }

        appendLine("```")
        appendLine()

        // Try to extract key metrics from the JSON for summary
        try {
            val jsonElement = jsonParser.parseToJsonElement(explainJson)
            val summary = extractExecutionSummary(jsonElement)
            if (summary.isNotEmpty()) {
                appendLine("⚡ Execution Summary:")
                summary.forEach { appendLine("  • $it") }
                appendLine()
            }
        } catch (e: Exception) {
            // If parsing fails, skip summary
        }

        appendLine("💡 Key Metrics to Look For:")
        appendLine("  • 'Actual Total Time' - Real execution time in milliseconds")
        appendLine("  • 'Actual Rows' vs 'Plan Rows' - Estimation accuracy")
        appendLine("  • 'Shared Hit Blocks' - Data found in memory cache")
        appendLine("  • 'Shared Read Blocks' - Data read from disk")
        appendLine("  • 'Node Type' - Operations like Seq Scan, Index Scan, etc.")
        appendLine("  • 'Startup Cost' vs 'Total Cost' - Query cost estimates")
        appendLine()
        appendLine("🔍 Performance Tips:")
        appendLine("  • High 'Shared Read Blocks' may indicate missing indexes")
        appendLine("  • 'Seq Scan' on large tables suggests index optimization needed")
        appendLine("  • Large difference between 'Plan Rows' and 'Actual Rows' indicates outdated statistics")
        appendLine("  • Consider running ANALYZE on tables with poor estimates")
    }
}

/**
 * Extract key execution metrics from EXPLAIN JSON for summary
 */
private fun extractExecutionSummary(jsonElement: JsonElement): List<String> {
    val summary = mutableListOf<String>()

    try {
        // Handle array of plans (typical EXPLAIN output format)
        val plans = if (jsonElement is JsonArray) {
            jsonElement
        } else {
            // Handle single plan object
            JsonArray(listOf(jsonElement))
        }

        plans.forEach { planElement ->
            if (planElement is JsonObject) {
                val plan = planElement["Plan"]?.jsonObject
                if (plan != null) {
                    extractPlanSummary(plan, summary)
                }
            }
        }
    } catch (e: Exception) {
        // If extraction fails, return empty summary
    }

    return summary
}

/**
 * Format single reconnection result
 */
private fun formatReconnectionResult(result: ReconnectionResult): String {
    return buildString {
        appendLine("=== Database Reconnection Result ===")
        appendLine()

        if (result.success) {
            appendLine("✅ SUCCESS: ${result.message}")
            if (result.connectionInfo != null) {
                appendLine("   Connection: ${result.connectionInfo}")
            }
        } else {
            appendLine("❌ FAILED: ${result.message}")
            if (result.error != null) {
                appendLine("   Error: ${result.error}")
            }
        }

        appendLine("   Environment: ${result.environment}")
        appendLine()

        if (result.success) {
            appendLine("🔧 Next Steps:")
            appendLine("   • Database connection is now active and ready for queries")
            appendLine("   • You can verify connectivity using postgres_connection_stats")
            appendLine("   • Try running a simple query to confirm functionality")
        } else {
            appendLine("🔧 Troubleshooting:")
            appendLine("   • Check VPN connection status")
            appendLine("   • Verify database.properties configuration")
            appendLine("   • Ensure database server is accessible")
            appendLine("   • Check network connectivity to database host")
        }
    }
}

/**
 * Format multiple reconnection results
 */
private fun formatMultipleReconnectionResults(
    results: List<ReconnectionResult>,
    healthStatus: Map<String, Boolean>? = null
): String {
    return buildString {
        appendLine("=== Database Reconnection Results ===")
        appendLine()

        if (healthStatus != null) {
            appendLine("Initial Health Check:")
            healthStatus.forEach { (env, healthy) ->
                appendLine("   • $env: ${if (healthy) "✓ Healthy" else "✗ Unhealthy"}")
            }
            appendLine()
        }

        val successfulReconnections = results.filter { it.success }
        val failedReconnections = results.filter { !it.success }

        appendLine("Reconnection Summary:")
        appendLine("   • Total environments: ${results.size}")
        appendLine("   • Successful: ${successfulReconnections.size}")
        appendLine("   • Failed: ${failedReconnections.size}")
        appendLine()

        if (successfulReconnections.isNotEmpty()) {
            appendLine("✅ SUCCESSFUL RECONNECTIONS:")
            successfulReconnections.forEach { result ->
                appendLine("   • ${result.environment}: ${result.message}")
                if (result.connectionInfo != null) {
                    appendLine("     Connection: ${result.connectionInfo}")
                }
            }
            appendLine()
        }

        if (failedReconnections.isNotEmpty()) {
            appendLine("❌ FAILED RECONNECTIONS:")
            failedReconnections.forEach { result ->
                appendLine("   • ${result.environment}: ${result.message}")
                if (result.error != null) {
                    appendLine("     Error: ${result.error}")
                }
            }
            appendLine()
        }

        if (successfulReconnections.isNotEmpty()) {
            appendLine("🔧 Next Steps:")
            appendLine("   • ${successfulReconnections.size} database connection(s) are now active")
            appendLine("   • Use postgres_connection_stats to verify pool status")
            appendLine("   • Test connectivity with simple queries")
        }

        if (failedReconnections.isNotEmpty()) {
            appendLine("🔧 Troubleshooting Failed Connections:")
            appendLine("   • Check VPN connection status")
            appendLine("   • Verify database.properties configuration for failed environments")
            appendLine("   • Ensure database servers are accessible")
            appendLine("   • Check network connectivity to database hosts")
            appendLine("   • Review server logs for connection errors")
        }
    }
}

/**
 * Extract summary information from a single plan node
 */
private fun extractPlanSummary(plan: JsonObject, summary: MutableList<String>) {
    try {
        val nodeType = plan["Node Type"]?.jsonPrimitive?.content
        val actualTime = plan["Actual Total Time"]?.jsonPrimitive?.doubleOrNull
        val actualRows = plan["Actual Rows"]?.jsonPrimitive?.longOrNull
        val planRows = plan["Plan Rows"]?.jsonPrimitive?.longOrNull
        val relationName = plan["Relation Name"]?.jsonPrimitive?.content
        val indexName = plan["Index Name"]?.jsonPrimitive?.content
        val sharedHitBlocks = plan["Shared Hit Blocks"]?.jsonPrimitive?.longOrNull
        val sharedReadBlocks = plan["Shared Read Blocks"]?.jsonPrimitive?.longOrNull

        // Build summary line
        val parts = mutableListOf<String>()

        if (nodeType != null) {
            parts.add(nodeType)
        }

        if (relationName != null) {
            parts.add("on $relationName")
        }

        if (indexName != null) {
            parts.add("using $indexName")
        }

        if (actualTime != null) {
            parts.add("${String.format("%.3f", actualTime)}ms")
        }

        if (actualRows != null && planRows != null) {
            val accuracy = if (planRows > 0) {
                val ratio = actualRows.toDouble() / planRows.toDouble()
                when {
                    ratio > 10 -> "⚠️ overestimated"
                    ratio < 0.1 -> "⚠️ underestimated"
                    else -> "✅ accurate"
                }
            } else "unknown"
            parts.add("$actualRows rows ($accuracy)")
        }

        if (sharedHitBlocks != null && sharedReadBlocks != null) {
            val totalBlocks = sharedHitBlocks + sharedReadBlocks
            if (totalBlocks > 0) {
                val cacheHitRatio = (sharedHitBlocks.toDouble() / totalBlocks.toDouble() * 100).toInt()
                parts.add("${cacheHitRatio}% cache hit")
            }
        }

        if (parts.isNotEmpty()) {
            summary.add(parts.joinToString(" - "))
        }

        // Recursively process child plans
        val plans = plan["Plans"]?.jsonArray
        plans?.forEach { childPlan ->
            if (childPlan is JsonObject) {
                extractPlanSummary(childPlan, summary)
            }
        }

    } catch (e: Exception) {
        // If extraction fails for this plan, continue with others
    }
}