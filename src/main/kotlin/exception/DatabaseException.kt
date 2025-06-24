package exception

/**
 * Custom exception for database operations
 * 
 * This exception is thrown when database operations fail, providing
 * context about the specific database error that occurred.
 * 
 * @param message The error message describing what went wrong
 * @param cause The underlying cause of the exception (optional)
 */
class DatabaseException(message: String, cause: Throwable? = null) : Exception(message, cause)
