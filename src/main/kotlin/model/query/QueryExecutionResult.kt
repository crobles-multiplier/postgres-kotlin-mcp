package model.query

import model.database.TableColumn

/**
 * Data class representing the result of a SQL query execution
 *
 * @property columns List of column metadata from the query result
 * @property rows List of result rows, each row represented as a map of column name to value
 * @property executionTimeMs Time taken to execute the query in milliseconds
 * @property rowCount Number of rows returned
 * @property hasMoreRows Whether there are more rows available beyond the limit
 */
data class QueryExecutionResult(
    val columns: List<TableColumn>,
    val rows: List<Map<String, Any?>>,
    val executionTimeMs: Long,
    val rowCount: Int,
    val hasMoreRows: Boolean
)
