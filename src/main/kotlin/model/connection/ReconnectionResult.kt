package model.connection

import model.Environment

/**
 * Data class representing the result of a database reconnection attempt
 *
 * This class encapsulates all information about a reconnection operation,
 * including success status, environment details, and error information.
 * Used by HikariConnectionManager for reconnection operations.
 *
 * @property success Whether the reconnection attempt was successful
 * @property environment The database environment that was targeted for reconnection
 * @property message Human-readable message describing the reconnection result
 * @property connectionInfo Optional connection details (host:port/database) when successful
 * @property error Optional error message when reconnection fails
 */
data class ReconnectionResult(
    val success: Boolean,
    val environment: Environment,
    val message: String,
    val connectionInfo: String? = null,
    val error: String? = null
) {
    

}
