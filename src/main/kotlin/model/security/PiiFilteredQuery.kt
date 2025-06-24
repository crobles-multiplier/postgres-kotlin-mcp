package model.security

/**
 * Data class containing information about a SQL query that has been processed for PII filtering
 *
 * This class represents the result of analyzing and potentially rewriting a SQL query
 * to exclude columns containing personally identifiable information (PII) in production environments.
 *
 * @property sql The final SQL query (potentially rewritten to exclude PII columns)
 * @property detectedPiiColumns List of column names that were detected as containing PII
 * @property filteredColumns List of column names that were actually filtered out from the query
 */
data class PiiFilteredQuery(
    val sql: String,
    val detectedPiiColumns: List<String>,
    val filteredColumns: List<String>
)
