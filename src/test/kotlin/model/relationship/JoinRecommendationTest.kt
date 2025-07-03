package model.relationship

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class JoinRecommendationTest {

    @Test
    fun `should create join recommendation with all properties`() {
        val joinRec = JoinRecommendation(
            fromTable = "users",
            toTable = "orders",
            joinCondition = "users.id = orders.user_id",
            joinType = "INNER JOIN"
        )
        
        assertEquals("users", joinRec.fromTable)
        assertEquals("orders", joinRec.toTable)
        assertEquals("users.id = orders.user_id", joinRec.joinCondition)
        assertEquals("INNER JOIN", joinRec.joinType)
    }

    @Test
    fun `should handle different join types`() {
        val innerJoin = JoinRecommendation("users", "profiles", "users.id = profiles.user_id", "INNER JOIN")
        val leftJoin = JoinRecommendation("users", "orders", "users.id = orders.user_id", "LEFT JOIN")
        val rightJoin = JoinRecommendation("orders", "users", "orders.user_id = users.id", "RIGHT JOIN")
        
        assertEquals("INNER JOIN", innerJoin.joinType)
        assertEquals("LEFT JOIN", leftJoin.joinType)
        assertEquals("RIGHT JOIN", rightJoin.joinType)
    }

    @Test
    fun `should handle complex join conditions`() {
        val complexJoin = JoinRecommendation(
            fromTable = "orders",
            toTable = "order_items",
            joinCondition = "orders.id = order_items.order_id AND orders.status = 'active'",
            joinType = "INNER JOIN"
        )
        
        assertEquals("orders.id = order_items.order_id AND orders.status = 'active'", complexJoin.joinCondition)
    }

    @Test
    fun `should handle table names with schemas`() {
        val schemaJoin = JoinRecommendation(
            fromTable = "public.users",
            toTable = "sales.orders",
            joinCondition = "public.users.id = sales.orders.user_id",
            joinType = "LEFT JOIN"
        )
        
        assertEquals("public.users", schemaJoin.fromTable)
        assertEquals("sales.orders", schemaJoin.toTable)
        assertEquals("public.users.id = sales.orders.user_id", schemaJoin.joinCondition)
    }
}
