# PostgreSQL MCP Server

A Model Context Protocol (MCP) server that provides tools to interact with PostgreSQL databases. Built using the official **Kotlin MCP SDK** for robust and standardized protocol compliance.

## Features

- **🔒 Safe Query Execution**: Only SELECT queries are allowed for security
- **🗄️ Schema Inspection**: Get detailed table schemas and column information  
- **📋 Table Listing**: List all tables in the database
- **🔗 Relationship Discovery**: Discover foreign keys, primary keys, and table relationships
- **🔄 JOIN Suggestions**: Get intelligent JOIN query suggestions based on relationships
- **🌍 Multi-Environment Support**: Connect to staging, release, and production databases
- **⚡ HikariCP Connection Pooling**: Enterprise-grade connection management
- **📊 Real-time Monitoring**: Built-in connection pool statistics and health monitoring

## Prerequisites

- Java 17 or higher
- PostgreSQL database(s)
- Database configuration file (`database.properties`)

## Available Tools

| Tool Name | Description | Required Parameters | Optional Parameters | Returns |
|-----------|-------------|-------------------|-------------------|---------|
| `postgres_query` | Execute SELECT queries against the database | `sql` (string) | `environment` (staging/release/production) | Query results in table format |
| `postgres_list_tables` | List all tables in the database | None | `environment` (staging/release/production) | List of table names |
| `postgres_get_table_schema` | Get detailed schema information for a table | `table_name` (string) | `environment` (staging/release/production) | Column details with data types, constraints, and relationship indicators |
| `postgres_get_relationships` | Get table relationships (FK, PK, constraints) | `table_name` (string) | `environment` (staging/release/production) | Primary keys, foreign keys, referenced by, unique constraints |
| `postgres_suggest_joins` | Suggest JOIN queries based on relationships | `table_name` (string) | `environment` (staging/release/production) | Suggested JOIN conditions and example queries |
| `postgres_connection_stats` | Get connection pool statistics and health info | None | None | HikariCP pool status, metrics, and health information |

### Safety Features
- **🔒 Read-Only Access**: Only SELECT queries are permitted
- **🛡️ SQL Injection Protection**: Uses parameterized queries where possible
- **📏 Row Limiting**: Configurable limits prevent overwhelming responses
- **✅ Connection Validation**: Built-in connection testing and validation

## Configuration

Create a `database.properties` file in `src/main/resources/` with your database connection details:

```properties
# PostgreSQL Database Configuration

# Database Connection URLs (include credentials in URL)
database.staging.url=postgresql://username:password@localhost:5432/mydb_staging
database.release.url=postgresql://username:password@localhost:5432/mydb_release
database.production.url=${POSTGRES_PRODUCTION_CONNECTION_STRING}

# HikariCP Connection Pool Configuration
# All properties are REQUIRED - no fallback defaults are provided

# Global HikariCP settings (required)
hikari.driver-class-name=org.postgresql.Driver
hikari.maximum-pool-size=10
hikari.minimum-idle=2
hikari.connection-timeout=30000
hikari.idle-timeout=600000
hikari.max-lifetime=1800000
hikari.validation-timeout=5000
hikari.leak-detection-threshold=60000

# Environment-specific HikariCP overrides (optional)
# Staging - smaller pool for development
hikari.staging.maximum-pool-size=5
hikari.staging.minimum-idle=1
hikari.staging.idle-timeout=300000
hikari.staging.max-lifetime=900000

# Release - medium pool for testing
hikari.release.maximum-pool-size=8
hikari.release.minimum-idle=2

# Production - larger pool, no leak detection for performance
hikari.production.maximum-pool-size=15
hikari.production.minimum-idle=3
# Note: leak-detection-threshold is automatically disabled for production
```

## Building

```bash
./gradlew shadowJar
```

This creates a fat JAR at `build/libs/postgres-mcp-tool-1.0-SNAPSHOT.jar`.

## Environment-Based Database Routing

All tools support environment-based database routing with an optional `environment` parameter:
- `staging` (default) - Routes to staging database
- `release` - Routes to release database  
- `production` - Routes to production database

### Natural Language Support
AI agents automatically extract environment information from user prompts:
- *"Query the production database for user statistics"* → `environment: "production"`
- *"List tables in staging"* → `environment: "staging"`
- *"Show me the schema for users table in release"* → `environment: "release"`

## Usage with AI Agents

Add this configuration to your AI agent's MCP configuration file:

```json
{
  "mcpServers": {
    "postgres-mcp-tool": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/postgres-mcp-tool-1.0-SNAPSHOT.jar"]
    }
  }
}
```

## Architecture

This server is built using:
- **Kotlin MCP SDK v0.5.0**: Official Model Context Protocol implementation
- **HikariCP**: High-performance JDBC connection pooling
- **PostgreSQL JDBC Driver**: Database connectivity
- **Kotlinx Serialization**: JSON handling
- **Kotlinx Coroutines**: Asynchronous operations

### Connection Management (HikariCP)
- **Enterprise-Grade Pooling**: Battle-tested connection management
- **Automatic Health Monitoring**: Built-in connection validation and health checks
- **Connection Leak Detection**: Automatically detects and reports connection leaks
- **Optimized Performance**: Fastest connection pool available for Java/Kotlin
- **Thread-Safe Operations**: Concurrent access is properly managed

## Example Usage

### Basic Database Exploration
- *"What tables are in my database?"*
- *"Show me the schema for the users table"*
- *"Query the first 10 rows from the products table"*

### Environment-Specific Queries
- *"What tables are in the production database?"*
- *"Query the staging database for user statistics"*
- *"Show me the schema for the orders table in release environment"*
- *"Get relationships for the users table in staging"*

### Relationship Discovery
- *"What are the relationships for the orders table?"*
- *"Show me all foreign keys in the customers table"*
- *"What tables reference the users table?"*
- *"What JOIN queries can I write with the orders table?"*

### Advanced Schema Analysis
- *"Show me the complete schema with relationships for the products table"*
- *"What are the primary keys and foreign keys for all my tables?"*
- *"Help me understand how my tables are connected"*

## Project Structure

```
postgres-mcp-tool/
├── src/
│   ├── main/
│   │   ├── kotlin/
│   │   │   ├── PostgreSqlMcpServer.kt      # Main MCP server implementation
│   │   │   ├── PostgreSqlRepository.kt     # Database operations and queries
│   │   │   ├── HikariConnectionManager.kt  # Connection pool management
│   │   │   └── DatabaseConfiguration.kt    # Configuration management
│   │   └── resources/
│   │       └── database.properties         # Database configuration
│   └── test/kotlin/
│       └── PostgreSqlMcpServerTest.kt      # Unit tests
├── build.gradle.kts                        # Build configuration
├── docker-compose.yml                      # Docker setup for testing
├── init.sql                                # Sample database schema
└── README.md                              # This documentation
```

## Troubleshooting

### Common Issues

1. **Connection Failed**: Check your connection string format and database accessibility
2. **Permission Denied**: Ensure the database user has SELECT permissions
3. **Tool Not Showing**: Verify the JAR path in your AI agent's MCP configuration
4. **Java Not Found**: Ensure Java 17+ is installed and in your PATH

### Debugging

Check your AI agent's logs for errors. For example:

**Claude Desktop:**
```bash
# macOS/Linux
tail -f ~/Library/Logs/Claude/mcp*.log

# Windows
# Check %APPDATA%\Claude\Logs\
```

**Other AI Agents:**
- Check your specific AI agent's documentation for log locations
- Look for MCP-related error messages in the agent's console or log files

## Migration to Kotlin MCP SDK

🎉 **Updated**: This server has been migrated from a custom JSON-RPC implementation to use the official **Kotlin MCP SDK**, providing:

### Benefits of the Migration
- **Better Protocol Compliance**: Full adherence to the MCP specification
- **Improved Error Handling**: Standardized error responses and better debugging
- **Cleaner Code Structure**: More maintainable and readable codebase
- **Future-Proof Compatibility**: Automatic updates with MCP protocol changes
- **Enhanced Performance**: Optimized transport layer and message handling

### What Changed
- **Server Implementation**: Now uses `Server` class from the official SDK
- **Tool Registration**: Tools are registered using `server.addTool()` method
- **Transport Layer**: Uses `StdioServerTransport` with proper kotlinx.io integration
- **Message Handling**: Automatic JSON-RPC protocol handling by the SDK

### What Stayed the Same
- **All existing functionality**: Every tool and feature has been preserved
- **Database operations**: HikariCP connection management unchanged
- **Configuration**: Same `database.properties` file format
- **API compatibility**: All tool parameters and responses remain identical

### Technical Details
- **SDK Version**: Using Kotlin MCP SDK v0.5.0
- **Transport**: STDIO transport with buffered kotlinx.io streams
- **Capabilities**: Tools with `listChanged` support
- **Error Handling**: Standardized MCP error responses

This migration ensures the server remains compatible with all MCP clients while benefiting from the official SDK's improvements and future updates.

## License

This project is licensed under the MIT License.
