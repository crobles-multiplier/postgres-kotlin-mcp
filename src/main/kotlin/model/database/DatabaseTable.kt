package model.database

/**
 * Data class representing a database table with its metadata
 *
 * @property name Table name
 * @property schema Schema name the table belongs to
 * @property type Table type (e.g., "TABLE", "VIEW", "SYSTEM TABLE")
 */
data class DatabaseTable(
    val name: String,
    val schema: String?,
    val type: String
)
