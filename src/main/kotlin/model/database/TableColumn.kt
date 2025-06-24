package model.database

import model.security.ColumnSensitivityInfo

/**
 * Data class representing a database table column with its metadata
 *
 * @property name Column name
 * @property type Column data type (e.g., VARCHAR, INTEGER, TIMESTAMP)
 * @property nullable Whether the column allows NULL values
 * @property defaultValue Default value for the column, if any
 * @property size Column size/length constraint, if applicable
 * @property comment Column comment/description, if any
 * @property sensitivityInfo PII/sensitivity information for the column, if available
 */
data class TableColumn(
    val name: String,
    val type: String,
    val nullable: Boolean,
    val defaultValue: String? = null,
    val size: Int? = null,
    val comment: String? = null,
    val sensitivityInfo: ColumnSensitivityInfo? = null
)
