package model.relationship

/**
 * Data class representing a unique constraint on a database table
 *
 * @property constraintName Name of the unique constraint
 * @property tableName Table that contains the unique constraint
 * @property columnName Column that is part of the unique constraint
 */
data class UniqueConstraint(
    val constraintName: String,
    val tableName: String,
    val columnName: String
)
