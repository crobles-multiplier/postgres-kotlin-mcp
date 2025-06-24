package model.relationship

/**
 * Data class representing a foreign key relationship between database tables
 *
 * @property constraintName Name of the foreign key constraint
 * @property sourceTable Table that contains the foreign key
 * @property sourceColumn Column in the source table that references another table
 * @property targetTable Table being referenced by the foreign key
 * @property targetColumn Column in the target table being referenced
 * @property onDelete Action to take when the referenced row is deleted (CASCADE, SET NULL, etc.)
 * @property onUpdate Action to take when the referenced row is updated (CASCADE, SET NULL, etc.)
 */
data class ForeignKeyRelationship(
    val constraintName: String,
    val sourceTable: String,
    val sourceColumn: String,
    val targetTable: String,
    val targetColumn: String,
    val onDelete: String?,
    val onUpdate: String?
)
