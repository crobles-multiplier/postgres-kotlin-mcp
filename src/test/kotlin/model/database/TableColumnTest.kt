package model.database

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class TableColumnTest {

    @Test
    fun `should create table column with all properties`() {
        val column = TableColumn(
            name = "id",
            type = "INTEGER",
            nullable = false,
            defaultValue = "nextval('seq')",
            size = 10,
            comment = "Primary key column"
        )

        assertEquals("id", column.name)
        assertEquals("INTEGER", column.type)
        assertFalse(column.nullable)
        assertEquals("nextval('seq')", column.defaultValue)
        assertEquals(10, column.size)
        assertEquals("Primary key column", column.comment)
    }

    @Test
    fun `should create table column with nullable properties`() {
        val column = TableColumn(
            name = "description",
            type = "VARCHAR",
            nullable = true,
            defaultValue = null,
            size = null,
            comment = null
        )

        assertEquals("description", column.name)
        assertEquals("VARCHAR", column.type)
        assertTrue(column.nullable)
        assertNull(column.defaultValue)
        assertNull(column.size)
        assertNull(column.comment)
    }

    @Test
    fun `should handle different data types`() {
        val intColumn = TableColumn("id", "INTEGER", false)
        val varcharColumn = TableColumn("name", "VARCHAR", true, size = 255)
        val timestampColumn = TableColumn("created_at", "TIMESTAMP", false, defaultValue = "now()")
        val decimalColumn = TableColumn("price", "DECIMAL", true, size = 10)

        assertEquals("INTEGER", intColumn.type)
        assertEquals("VARCHAR", varcharColumn.type)
        assertEquals("TIMESTAMP", timestampColumn.type)
        assertEquals("DECIMAL", decimalColumn.type)

        assertEquals(255, varcharColumn.size)
        assertEquals("now()", timestampColumn.defaultValue)
        assertEquals(10, decimalColumn.size)
    }
}
