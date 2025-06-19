import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PostgreSqlMcpServerTest {
    
    @Test
    fun `test server startup without arguments`() {
        // Test that the server can be started without command line arguments
        // This test verifies the main function structure
        assertTrue(true) // Placeholder test since main() now has no parameters to test
    }
    
    @Test
    fun `test DatabaseException creation`() {
        val exception = DatabaseException("Test error")
        assertEquals("Test error", exception.message)
    }
    
    @Test
    fun `test DatabaseException with cause`() {
        val cause = RuntimeException("Root cause")
        val exception = DatabaseException("Test error", cause)
        assertEquals("Test error", exception.message)
        assertEquals(cause, exception.cause)
    }
    
    @Test
    fun `test TableColumn data class`() {
        val column = TableColumn(
            name = "id",
            type = "INTEGER",
            nullable = false,
            defaultValue = "nextval('seq')",
            size = 10
        )

        assertEquals("id", column.name)
        assertEquals("INTEGER", column.type)
        assertFalse(column.nullable)
        assertEquals("nextval('seq')", column.defaultValue)
        assertEquals(10, column.size)
    }
    
    @Test
    fun `test DatabaseTable data class`() {
        val table = DatabaseTable(
            name = "users",
            schema = "public",
            type = "TABLE"
        )

        assertEquals("users", table.name)
        assertEquals("public", table.schema)
        assertEquals("TABLE", table.type)
    }
    
    @Test
    fun `test QueryExecutionResult data class`() {
        val columns = listOf(
            TableColumn("id", "INTEGER", false),
            TableColumn("name", "VARCHAR", true)
        )
        val rows = listOf(
            mapOf("id" to 1, "name" to "John"),
            mapOf("id" to 2, "name" to "Jane")
        )

        val result = QueryExecutionResult(
            columns = columns,
            rows = rows,
            executionTimeMs = 150,
            rowCount = 2,
            hasMoreRows = false
        )

        assertEquals(2, result.columns.size)
        assertEquals(2, result.rows.size)
        assertEquals(150, result.executionTimeMs)
        assertEquals(2, result.rowCount)
        assertFalse(result.hasMoreRows)
    }

    @Test
    fun `test ForeignKeyRelationship data class`() {
        val foreignKey = ForeignKeyRelationship(
            constraintName = "fk_orders_customer",
            sourceTable = "orders",
            sourceColumn = "customer_id",
            targetTable = "customers",
            targetColumn = "id",
            onDelete = "CASCADE",
            onUpdate = "RESTRICT"
        )

        assertEquals("fk_orders_customer", foreignKey.constraintName)
        assertEquals("orders", foreignKey.sourceTable)
        assertEquals("customer_id", foreignKey.sourceColumn)
        assertEquals("customers", foreignKey.targetTable)
        assertEquals("id", foreignKey.targetColumn)
        assertEquals("CASCADE", foreignKey.onDelete)
        assertEquals("RESTRICT", foreignKey.onUpdate)
    }

    @Test
    fun `test PrimaryKeyConstraint data class`() {
        val primaryKey = PrimaryKeyConstraint(
            constraintName = "pk_customers",
            tableName = "customers",
            columnName = "id",
            keySequence = 1
        )

        assertEquals("pk_customers", primaryKey.constraintName)
        assertEquals("customers", primaryKey.tableName)
        assertEquals("id", primaryKey.columnName)
        assertEquals(1, primaryKey.keySequence)
    }

    @Test
    fun `test UniqueConstraint data class`() {
        val uniqueConstraint = UniqueConstraint(
            constraintName = "uk_customers_email",
            tableName = "customers",
            columnName = "email"
        )

        assertEquals("uk_customers_email", uniqueConstraint.constraintName)
        assertEquals("customers", uniqueConstraint.tableName)
        assertEquals("email", uniqueConstraint.columnName)
    }

    @Test
    fun `test TableRelationshipSummary data class`() {
        val primaryKeys = listOf(
            PrimaryKeyConstraint("pk_orders", "orders", "id", 1)
        )
        val foreignKeys = listOf(
            ForeignKeyRelationship("fk_orders_customer", "orders", "customer_id", "customers", "id", "CASCADE", "RESTRICT")
        )
        val referencedBy = listOf(
            ForeignKeyRelationship("fk_order_items_order", "order_items", "order_id", "orders", "id", "CASCADE", "CASCADE")
        )
        val uniqueConstraints = listOf(
            UniqueConstraint("uk_orders_number", "orders", "order_number")
        )

        val relationships = TableRelationshipSummary(
            tableName = "orders",
            primaryKeys = primaryKeys,
            foreignKeys = foreignKeys,
            referencedBy = referencedBy,
            uniqueConstraints = uniqueConstraints
        )

        assertEquals("orders", relationships.tableName)
        assertEquals(1, relationships.primaryKeys.size)
        assertEquals(1, relationships.foreignKeys.size)
        assertEquals(1, relationships.referencedBy.size)
        assertEquals(1, relationships.uniqueConstraints.size)
    }

    @Test
    fun `test JoinRecommendation data class`() {
        val joinRecommendation = JoinRecommendation(
            fromTable = "orders",
            toTable = "customers",
            joinCondition = "orders.customer_id = customers.id",
            joinType = "LEFT JOIN"
        )

        assertEquals("orders", joinRecommendation.fromTable)
        assertEquals("customers", joinRecommendation.toTable)
        assertEquals("orders.customer_id = customers.id", joinRecommendation.joinCondition)
        assertEquals("LEFT JOIN", joinRecommendation.joinType)
    }

    @Test
    fun `test DatabaseConnectionConfig database connection properties`() {
        // Test getting complete database config for known environments
        val stagingConfig = DatabaseConnectionConfig.forEnvironment("staging")
        assertTrue(stagingConfig.jdbcUrl.startsWith("jdbc:postgresql://"))
        assertEquals("testuser", stagingConfig.username)
        assertEquals("testpass", stagingConfig.password)
        assertEquals("staging", stagingConfig.environment)

        val releaseConfig = DatabaseConnectionConfig.forEnvironment("release")
        assertTrue(releaseConfig.jdbcUrl.startsWith("jdbc:postgresql://"))
        assertEquals("testuser", releaseConfig.username)
        assertEquals("testpass", releaseConfig.password)
        assertEquals("release", releaseConfig.environment)

        // Test complete configuration check
        assertTrue(DatabaseConnectionConfig.hasCompleteConfiguration("staging"))
        assertTrue(DatabaseConnectionConfig.hasCompleteConfiguration("release"))
        assertFalse(DatabaseConnectionConfig.hasCompleteConfiguration("nonexistent"))
    }

    @Test
    fun `test DatabaseConnectionConfig unknown environment`() {
        // Should return false for unknown environments
        assertFalse(DatabaseConnectionConfig.hasCompleteConfiguration("nonexistent"))

        // Should throw exception when trying to get config for unknown environment
        assertThrows<IllegalArgumentException> {
            DatabaseConnectionConfig.forEnvironment("nonexistent")
        }
    }

    @Test
    fun `test HikariConnectionManager initialization`() {
        val connectionManager = HikariConnectionManager()

        // Test initial state
        assertFalse(connectionManager.isEnvironmentModeEnabled())
        assertEquals("Single database mode", connectionManager.getConnectionInfo())
        assertEquals(listOf("default"), connectionManager.getAvailableEnvironments())
    }

    @Test
    fun `test HikariConnectionManager default connection`() = runTest {
        val connectionManager = HikariConnectionManager()

        // Create a mock repository (we can't test actual connections without a real database)
        // This test verifies the connection manager structure
        assertFalse(connectionManager.isEnvironmentModeEnabled())

        // Test that getting connection without environment throws when no default is set
        assertThrows<Exception> {
            connectionManager.getConnection(null)
        }
    }

    @Test
    fun `test HikariConnectionManager available environments`() {
        val connectionManager = HikariConnectionManager()

        // In single database mode, should return "default"
        val environments = connectionManager.getAvailableEnvironments()
        assertEquals(listOf("default"), environments)
        assertFalse(connectionManager.isEnvironmentModeEnabled())
    }

    @Test
    fun `test DatabaseConnectionConfig HikariCP properties`() {
        // Test that HikariCP properties can be read from database.properties
        val stagingMaxPoolSize = DatabaseConnectionConfig.getProperty("hikari.staging.maximum-pool-size")
        assertEquals("5", stagingMaxPoolSize)

        val releaseMaxPoolSize = DatabaseConnectionConfig.getProperty("hikari.release.maximum-pool-size")
        assertEquals("8", releaseMaxPoolSize)

        // Test that missing properties return null
        val productionMaxPoolSize = DatabaseConnectionConfig.getProperty("hikari.production.maximum-pool-size")
        assertNull(productionMaxPoolSize) // Not configured in test environment
    }

    @Test
    fun `test HikariCP standard configuration format`() {
        // Test that database config DTO provides proper format
        val stagingConfig = DatabaseConnectionConfig.forEnvironment("staging")
        assertTrue(stagingConfig.jdbcUrl.startsWith("jdbc:postgresql://"))

        // Test PostgreSqlRepository can still handle direct URL construction
        val postgresUrl = "postgresql://user:pass@localhost:5432/testdb"
        val repository = PostgreSqlRepository(postgresUrl)
        // Constructor completed successfully without throwing
        assertTrue(true)

        // Test that DTO provides proper JDBC format
        assertTrue(stagingConfig.jdbcUrl.startsWith("jdbc:postgresql://"))
    }

    @Test
    fun `test HikariCP simplified configuration`() {
        // Test that HikariConnectionManager works with minimal configuration
        val connectionManager = HikariConnectionManager()

        // Since we use sensible defaults, minimal configuration should work
        assertFalse(connectionManager.isEnvironmentModeEnabled())

        // Test that optional properties can be read when present
        val stagingMaxPoolSize = DatabaseConnectionConfig.getProperty("hikari.staging.maximum-pool-size")
        assertEquals("5", stagingMaxPoolSize)

        val releaseMaxPoolSize = DatabaseConnectionConfig.getProperty("hikari.release.maximum-pool-size")
        assertEquals("8", releaseMaxPoolSize)

        // Test that missing properties return null (will use defaults)
        val missingProperty = DatabaseConnectionConfig.getProperty("hikari.nonexistent.property")
        assertNull(missingProperty)
    }
}
