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
| `postgres_suggest_joins` | Suggest JOIN queries based on relationships | `table_name` (string) | `environment` (staging/release/production) | Suggested JOIN conditions and example queries |
| `postgres_connection_stats` | Get connection pool statistics and health info | None | None | HikariCP pool status, metrics, and health information |
| `postgres_get_pii_columns` | Get PII column information based on database comments | `table_name` (string) | `environment` (staging/release/production) | List of PII and non-PII columns with sensitivity levels |
| `postgres_explain_query` | Get PostgreSQL query execution plan with performance analysis | `sql` (string) | `environment` (staging/release/production) | Detailed execution plan with timing, costs, and optimization insights |
| `postgres_reconnect_database` | Reconnect to database(s) - useful when VPN connection is restored | None | `environment` (staging/release/production), `test_connection` (boolean) | Reconnection status and results for specified or all environments |

### Safety Features
- **🔒 Read-Only Access**: Only SELECT queries are permitted
- **🛡️ SQL Injection Protection**: Uses parameterized queries where possible
- **📏 Row Limiting**: Configurable limits prevent overwhelming responses
- **✅ Connection Validation**: Built-in connection testing and validation
- **🔄 Automatic Reconnection**: Smart reconnection handling for VPN and network issues

## Database Reconnection & Startup Resilience

### Graceful Startup
The MCP server now starts successfully **even when no database connections can be established initially**. This is particularly useful for VPN-dependent environments where:
- VPN might not be connected at startup
- Database servers might be temporarily unavailable
- Network connectivity issues exist

**Previous Behavior**: Server would fail to start if no connections could be established
**New Behavior**: Server starts with a warning and provides reconnection tools

### Reconnection Tool
The `postgres_reconnect_database` tool provides robust reconnection capabilities for handling VPN disconnections and network issues:

#### Features
- **Smart Health Checking**: Tests connection health before attempting reconnection
- **Selective Reconnection**: Reconnect specific environments or all environments
- **Detailed Status Reporting**: Comprehensive feedback on reconnection attempts
- **VPN-Aware**: Designed specifically for VPN connection scenarios
- **Graceful Error Handling**: Helpful error messages guide users to reconnection tools

#### Usage Examples
- *"Reconnect to the database"* - Reconnects all environments with health checking
- *"Reconnect to production database"* - Reconnects only production environment
- *"Force reconnect all databases without testing"* - Bypasses health check and reconnects all
- *"Test and reconnect staging database"* - Tests staging connection health and reconnects if needed
- *"Check connection status"* - Use `postgres_connection_stats` to see current status

#### When to Use
- After VPN reconnection
- When experiencing database connectivity issues
- After network interruptions
- When connection pools show unhealthy status
- Before critical database operations
- When MCP server started without valid connections

#### Parameters
- `environment` (optional): Specific environment to reconnect (staging/release/production)
- `test_connection` (optional): Whether to test connection health first (default: true)

If no environment is specified, the tool will check and reconnect all configured environments as needed.

#### Startup Workflow
1. **Server Starts**: MCP server starts regardless of database connectivity
2. **Warning Displayed**: If no connections available, shows helpful warning message
3. **Tools Available**: All MCP tools are registered and ready to use
4. **Reconnection**: Use `postgres_reconnect_database` when ready to connect
5. **Normal Operation**: Once connected, all database tools work normally

## Configuration

Create a `database.properties` file in `src/main/resources/` with your database connection details:

```properties
# PostgreSQL Database Configuration
# All sensitive information should be provided via environment variables

# Staging Environment
database.staging.jdbc-url=${POSTGRES_STAGING_JDBC_URL}
database.staging.username=${POSTGRES_STAGING_USERNAME}
database.staging.password=${POSTGRES_STAGING_PASSWORD}

# Release Environment
database.release.jdbc-url=${POSTGRES_RELEASE_JDBC_URL}
database.release.username=${POSTGRES_RELEASE_USERNAME}
database.release.password=${POSTGRES_RELEASE_PASSWORD}

# Production Environment
database.production.jdbc-url=${POSTGRES_PRODUCTION_JDBC_URL}
database.production.username=${POSTGRES_PRODUCTION_USERNAME}
database.production.password=${POSTGRES_PRODUCTION_PASSWORD}

# HikariCP Connection Pool Configuration (Optional)
# Uses sensible defaults if not specified

# Optional: Override default pool sizes per environment
hikari.staging.maximum-pool-size=5
hikari.staging.minimum-idle=1

hikari.release.maximum-pool-size=8
hikari.release.minimum-idle=2

hikari.production.maximum-pool-size=15
hikari.production.minimum-idle=3
```

### Environment Variables Setup

Set the required environment variables for your database connections:

```bash
# Staging Environment
export POSTGRES_STAGING_JDBC_URL="jdbc:postgresql://localhost:5432/mydb_staging"
export POSTGRES_STAGING_USERNAME="your_staging_username"
export POSTGRES_STAGING_PASSWORD="your_staging_password"

# Release Environment
export POSTGRES_RELEASE_JDBC_URL="jdbc:postgresql://localhost:5432/mydb_release"
export POSTGRES_RELEASE_USERNAME="your_release_username"
export POSTGRES_RELEASE_PASSWORD="your_release_password"

# Production Environment
export POSTGRES_PRODUCTION_JDBC_URL="jdbc:postgresql://prod-host:5432/mydb_production"
export POSTGRES_PRODUCTION_USERNAME="your_production_username"
export POSTGRES_PRODUCTION_PASSWORD="your_production_password"
```

**For Windows (PowerShell):**
```powershell
$env:POSTGRES_STAGING_JDBC_URL="jdbc:postgresql://localhost:5432/mydb_staging"
$env:POSTGRES_STAGING_USERNAME="your_staging_username"
$env:POSTGRES_STAGING_PASSWORD="your_staging_password"
# ... repeat for release and production
```

**For Docker/Container environments:**
```yaml
environment:
  - POSTGRES_STAGING_JDBC_URL=jdbc:postgresql://localhost:5432/mydb_staging
  - POSTGRES_STAGING_USERNAME=your_staging_username
  - POSTGRES_STAGING_PASSWORD=your_staging_password
```

## PII Column Detection

This MCP server includes a tool to detect and analyze PII (Personally Identifiable Information) columns based on database column comments:

### How It Works
- **Comment-Based Detection**: The `postgres_get_pii_columns` tool reads column comments containing JSON privacy information
- **Privacy Field Analysis**: Analyzes the `privacy` field in column comments to determine PII status
- **Simple Classification**: Columns marked as `"privacy": "personal"` are treated as PII, `"privacy": "non-personal"` are safe

### Using PII Detection
1. **Query column information**: Use `postgres_get_pii_columns` tool to analyze table columns
2. **Review results**: See which columns are marked as PII vs non-PII based on existing comments
3. **Compliance support**: Use for data auditing and classification purposes

### Example Usage
- *"Show me which columns in the users table contain PII"*
- *"What's the sensitivity level of columns in the orders table?"*
- *"List all non-personal columns in the customers table"*

## Building

The build system requires a `jarSuffix` parameter to create database-specific JAR files:

```bash
# Build JAR for specific database system
./gradlew shadowJar -PjarSuffix=incidents
./gradlew shadowJar -PjarSuffix=users
./gradlew shadowJar -PjarSuffix=analytics
./gradlew shadowJar -PjarSuffix=payroll
```

This creates JAR files with descriptive names:
- `build/libs/postgres-mcp-tool-incidents.jar`
- `build/libs/postgres-mcp-tool-users.jar`
- `build/libs/postgres-mcp-tool-analytics.jar`
- `build/libs/postgres-mcp-tool-payroll.jar`

**Note**: The `jarSuffix` parameter is required. Running `./gradlew shadowJar` without it will fail with a clear error message.

### Multi-Database Workflow

This naming convention enables you to manage multiple database systems efficiently:

1. **Configure** your `database.properties` for the target database system
2. **Build** the JAR with a descriptive suffix: `./gradlew shadowJar -PjarSuffix=incidents`
3. **Repeat** for other database systems (users, analytics, payroll, etc.)
4. **Deploy** multiple MCP servers, each with its own JAR and database configuration
5. **Distinguish** easily between different database connections in your AI agent

**Example Workflow:**
```bash
# Configure database.properties for incidents database
# Build incidents JAR
./gradlew shadowJar -PjarSuffix=incidents

# Update database.properties for users database
# Build users JAR
./gradlew shadowJar -PjarSuffix=users

# Update database.properties for analytics database
# Build analytics JAR
./gradlew shadowJar -PjarSuffix=analytics
```

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

### Claude Desktop Configuration

Add to your `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "postgres-incidents": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/postgres-mcp-tool-incidents.jar"]
    },
    "postgres-users": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/postgres-mcp-tool-users.jar"]
    },
    "postgres-analytics": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/postgres-mcp-tool-analytics.jar"]
    }
  }
}
```

**Single Database Setup:**
```json
{
  "mcpServers": {
    "postgres-mcp-tool": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/postgres-mcp-tool-incidents.jar"]
    }
  }
}
```

### Augment IntelliJ Plugin Configuration

In the Augment IntelliJ plugin, add MCP servers for each database system:

**For Incidents Database:**
- **Name**: `postgres-incidents`
- **Command**: `java -jar /absolute/path/to/postgres-mcp-tool-incidents.jar`

**For Users Database:**
- **Name**: `postgres-users`
- **Command**: `java -jar /absolute/path/to/postgres-mcp-tool-users.jar`

**For Analytics Database:**
- **Name**: `postgres-analytics`
- **Command**: `java -jar /absolute/path/to/postgres-mcp-tool-analytics.jar`

### Other AI Agents

For other MCP-compatible AI agents, use the standard MCP server configuration format:

**Multiple Database Systems:**
```json
[
  {
    "name": "postgres-incidents",
    "command": "java",
    "args": ["-jar", "/absolute/path/to/postgres-mcp-tool-incidents.jar"],
    "env": {}
  },
  {
    "name": "postgres-users",
    "command": "java",
    "args": ["-jar", "/absolute/path/to/postgres-mcp-tool-users.jar"],
    "env": {}
  }
]
```

**Single Database System:**
```json
{
  "name": "postgres-mcp-tool",
  "command": "java",
  "args": ["-jar", "/absolute/path/to/postgres-mcp-tool-incidents.jar"],
  "env": {}
}
```

### Prerequisites

Before using the MCP server, ensure you have:

1. **Java 17+** installed and available in your PATH
2. **Built the JAR file** using `./gradlew shadowJar -PjarSuffix=<database-name>`
3. **Set environment variables** for your database connections (see Environment Variables Setup above)
4. **Database permissions** - the configured user must have SELECT permissions on the target databases

### Database-Specific JAR Management

Since you can create multiple JAR files for different database systems, you can:

1. **Build separate JARs** for each database system (incidents, users, analytics, etc.)
2. **Configure different database.properties** for each system before building
3. **Deploy multiple MCP servers** simultaneously, each connecting to different databases
4. **Easily distinguish** between database connections using descriptive JAR names

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
- *"What database am I connected to?"*
- *"What tables are in my database?"*
- *"Show me the schema for the users table"*
- *"Query the first 10 rows from the products table"*

### Environment-Specific Queries
- *"What tables are in the production database?"*
- *"Query the staging database for user statistics"*
- *"Show me the schema for the orders table in release environment"*
### Relationship Discovery
- *"Show me all foreign keys in the customers table"*
- *"What tables reference the users table?"*
- *"What JOIN queries can I write with the orders table?"*


### Advanced Schema Analysis
- *"Show me the complete schema with relationships for the products table"*
- *"What are the primary keys and foreign keys for all my tables?"*
- *"Help me understand how my tables are connected"*

### Query Performance Analysis
- *"Explain the execution plan for this query: SELECT * FROM users WHERE email = 'test@example.com'"*
- *"Analyze the performance of my complex JOIN query"*
- *"Show me why this query is slow and how to optimize it"*
- *"Get the execution plan for my query in the production database"*

## Project Structure

```
postgres-mcp-tool/
├── src/
│   ├── main/
│   │   ├── kotlin/
│   │   │   ├── PostgreSqlMcpServer.kt         # Main MCP server implementation
│   │   │   ├── PostgreSqlRepository.kt        # Database operations and queries
│   │   │   ├── HikariConnectionManager.kt     # Connection pool management
│   │   │   └── DatabaseConnectionConfig.kt    # Database configuration DTO
│   │   └── resources/
│   │       └── database.properties            # Database configuration
│   └── test/kotlin/
│       ├── PostgreSqlMcpServerTest.kt         # Integration tests
│       ├── DatabaseConnectionConfigTest.kt    # DTO unit tests
│       └── DatabaseConfigurationTest.kt       # Configuration integration tests
├── build.gradle.kts                           # Build configuration
├── docker-compose.yml                         # Docker setup for testing
├── init.sql                                   # Sample database schema
└── README.md                                 # This documentation
```

## Troubleshooting

### Common Issues

1. **No Database Connections at Startup**:
   - **New Behavior**: Server starts successfully with a warning message
   - Use `postgres_reconnect_database` tool to establish connections when ready
   - Common causes: VPN not connected, database servers unavailable, network issues

2. **Connection Failed During Operation**:
   - Use `postgres_connection_stats` to check current connection status
   - Use `postgres_reconnect_database` to restore connections
   - Check that all required environment variables are set
   - Verify JDBC URL format: `jdbc:postgresql://host:port/database`
   - Ensure database credentials are correct

3. **VPN Connection Issues**:
   - Connect VPN first, then use `postgres_reconnect_database`
   - Server will start without VPN and allow reconnection later
   - Use connection health checking to verify VPN connectivity

4. **Environment Variables Not Found**:
   - Verify environment variables are exported in your shell
   - For AI agents, ensure environment variables are available to the Java process

5. **Permission Denied**: Ensure the database user has SELECT permissions

6. **Tool Not Showing**: Verify the JAR path in your AI agent's MCP configuration

7. **Java Not Found**: Ensure Java 17+ is installed and in your PATH

8. **Build Failed - Missing jarSuffix**:
   - Use `./gradlew shadowJar -PjarSuffix=<database-name>` instead of `./gradlew shadowJar`
   - The jarSuffix parameter is required to create descriptive JAR names

9. **Wrong Database Connection**:
   - Verify you're using the correct JAR file for the intended database system
   - Check the JAR filename matches your database system (e.g., `postgres-mcp-tool-incidents.jar` for incidents database)

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
- **Configuration**: Clean `database.properties` with simplified format
- **API compatibility**: All tool parameters and responses remain identical

### Technical Details
- **SDK Version**: Using Kotlin MCP SDK v0.5.0
- **Transport**: STDIO transport with buffered kotlinx.io streams
- **Capabilities**: Tools with `listChanged` support
- **Error Handling**: Standardized MCP error responses

This migration ensures the server remains compatible with all MCP clients while benefiting from the official SDK's improvements and future updates.

## License

This project is licensed under the MIT License.
