package model.relationship

/**
 * Data class representing a recommended JOIN operation between database tables
 *
 * @property fromTable Source table for the JOIN operation
 * @property toTable Target table to JOIN with
 * @property joinCondition SQL condition for the JOIN (e.g., "table1.id = table2.foreign_id")
 * @property joinType Type of JOIN to perform (default: "INNER JOIN")
 */
data class JoinRecommendation(
    val fromTable: String,
    val toTable: String,
    val joinCondition: String,
    val joinType: String = "INNER JOIN"
)
