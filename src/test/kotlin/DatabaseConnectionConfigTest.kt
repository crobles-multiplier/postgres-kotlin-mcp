import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows
import model.connection.DatabaseConnectionConfig

class DatabaseConnectionConfigTest {

    @Test
    fun `should create valid database connection config`() {
        val config = DatabaseConnectionConfig(
            jdbcUrl = "jdbc:postgresql://localhost:5432/testdb",
            username = "testuser",
            password = "testpass",
            environment = "staging"
        )

        assertEquals("jdbc:postgresql://localhost:5432/testdb", config.jdbcUrl)
        assertEquals("testuser", config.username)
        assertEquals("testpass", config.password)
        assertEquals("staging", config.environment)
    }

    @Test
    fun `should extract host from JDBC URL`() {
        val config = DatabaseConnectionConfig(
            jdbcUrl = "jdbc:postgresql://db.example.com:5432/mydb",
            username = "user",
            password = "pass",
            environment = "test"
        )

        assertEquals("db.example.com", config.getHost())
    }

    @Test
    fun `should extract port from JDBC URL`() {
        val config = DatabaseConnectionConfig(
            jdbcUrl = "jdbc:postgresql://localhost:5433/testdb",
            username = "user",
            password = "pass",
            environment = "test"
        )

        assertEquals(5433, config.getPort())
    }

    @Test
    fun `should extract database name from JDBC URL`() {
        val config = DatabaseConnectionConfig(
            jdbcUrl = "jdbc:postgresql://localhost:5432/production_db",
            username = "user",
            password = "pass",
            environment = "test"
        )

        assertEquals("production_db", config.getDatabaseName())
    }

    @Test
    fun `should extract connection parameters from JDBC URL`() {
        val config = DatabaseConnectionConfig(
            jdbcUrl = "jdbc:postgresql://localhost:5432/testdb?ssl=true&connectTimeout=30",
            username = "user",
            password = "pass",
            environment = "test"
        )

        val params = config.getConnectionParameters()
        assertEquals("true", params["ssl"])
        assertEquals("30", params["connectTimeout"])
    }

    @Test
    fun `should return empty map when no connection parameters`() {
        val config = DatabaseConnectionConfig(
            jdbcUrl = "jdbc:postgresql://localhost:5432/testdb",
            username = "user",
            password = "pass",
            environment = "test"
        )

        assertTrue(config.getConnectionParameters().isEmpty())
    }

    @Test
    fun `should mask password in toString`() {
        val config = DatabaseConnectionConfig(
            jdbcUrl = "jdbc:postgresql://localhost:5432/testdb",
            username = "testuser",
            password = "secretpassword",
            environment = "staging"
        )

        val maskedString = config.toMaskedString()
        assertTrue(maskedString.contains("testuser"))
        assertTrue(maskedString.contains("***"))
        assertFalse(maskedString.contains("secretpassword"))
    }

    @Test
    fun `should validate JDBC URL format`() {
        assertThrows<IllegalArgumentException> {
            DatabaseConnectionConfig(
                jdbcUrl = "invalid-url",
                username = "user",
                password = "pass",
                environment = "test"
            )
        }
    }

    @Test
    fun `should validate blank JDBC URL`() {
        assertThrows<IllegalArgumentException> {
            DatabaseConnectionConfig(
                jdbcUrl = "",
                username = "user",
                password = "pass",
                environment = "test"
            )
        }
    }

    @Test
    fun `should validate blank username`() {
        assertThrows<IllegalArgumentException> {
            DatabaseConnectionConfig(
                jdbcUrl = "jdbc:postgresql://localhost:5432/testdb",
                username = "",
                password = "pass",
                environment = "test"
            )
        }
    }

    @Test
    fun `should validate blank password`() {
        assertThrows<IllegalArgumentException> {
            DatabaseConnectionConfig(
                jdbcUrl = "jdbc:postgresql://localhost:5432/testdb",
                username = "user",
                password = "",
                environment = "test"
            )
        }
    }

    @Test
    fun `should validate blank environment`() {
        assertThrows<IllegalArgumentException> {
            DatabaseConnectionConfig(
                jdbcUrl = "jdbc:postgresql://localhost:5432/testdb",
                username = "user",
                password = "pass",
                environment = ""
            )
        }
    }

    @Test
    fun `should create config using factory method with valid properties`() {
        val config = DatabaseConnectionConfig.create(
            jdbcUrl = "jdbc:postgresql://localhost:5432/testdb",
            username = "testuser",
            password = "testpass",
            environment = "staging"
        )

        assertEquals("testuser", config.username)
        assertEquals("staging", config.environment)
    }

    @Test
    fun `should throw exception when creating with null properties`() {
        assertThrows<IllegalArgumentException> {
            DatabaseConnectionConfig.create(
                jdbcUrl = null,
                username = "user",
                password = "pass",
                environment = "test"
            )
        }
    }

    @Test
    fun `should throw exception when creating with blank properties`() {
        assertThrows<IllegalArgumentException> {
            DatabaseConnectionConfig.create(
                jdbcUrl = "jdbc:postgresql://localhost:5432/testdb",
                username = "",
                password = "pass",
                environment = "test"
            )
        }
    }




}
