package model.relationship

/**
 * Data class representing a primary key constraint on a database table
 *
 * @property constraintName Name of the primary key constraint
 * @property tableName Table that contains the primary key
 * @property columnName Column that is part of the primary key
 * @property keySequence Position of this column in a composite primary key (1-based)
 */
data class PrimaryKeyConstraint(
    val constraintName: String,
    val tableName: String,
    val columnName: String,
    val keySequence: Int
)
