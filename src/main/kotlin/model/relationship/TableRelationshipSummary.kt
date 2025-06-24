package model.relationship

/**
 * Data class containing a comprehensive summary of all relationships for a database table
 *
 * @property tableName Name of the table being analyzed
 * @property primaryKeys List of primary key constraints on this table
 * @property foreignKeys List of foreign key relationships where this table references other tables
 * @property referencedBy List of foreign key relationships where other tables reference this table
 * @property uniqueConstraints List of unique constraints on this table
 */
data class TableRelationshipSummary(
    val tableName: String,
    val primaryKeys: List<PrimaryKeyConstraint>,
    val foreignKeys: List<ForeignKeyRelationship>,
    val referencedBy: List<ForeignKeyRelationship>,
    val uniqueConstraints: List<UniqueConstraint>
)
