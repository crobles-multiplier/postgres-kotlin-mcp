-- Initialize test database with sample data for PostgreSQL MCP Tool testing

-- Create a users table
CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    username VARCHAR(50) UNIQUE NOT NULL,
    email VARCHAR(100) UNIQUE NOT NULL,
    full_name VARCHAR(100),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_active BOOLEAN DEFAULT true
);

-- Create a products table
CREATE TABLE products (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    price DECIMAL(10, 2) NOT NULL,
    category VARCHAR(50),
    stock_quantity INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Create an orders table
CREATE TABLE orders (
    id SERIAL PRIMARY KEY,
    user_id INTEGER REFERENCES users(id),
    total_amount DECIMAL(10, 2) NOT NULL,
    status VARCHAR(20) DEFAULT 'pending',
    order_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Insert sample users
INSERT INTO users (username, email, full_name) VALUES
    ('john_doe', 'john@example.com', 'John Doe'),
    ('jane_smith', 'jane@example.com', 'Jane Smith'),
    ('bob_wilson', 'bob@example.com', 'Bob Wilson'),
    ('alice_brown', 'alice@example.com', 'Alice Brown'),
    ('charlie_davis', 'charlie@example.com', 'Charlie Davis');

-- Insert sample products
INSERT INTO products (name, description, price, category, stock_quantity) VALUES
    ('Laptop', 'High-performance laptop for work and gaming', 1299.99, 'Electronics', 50),
    ('Smartphone', 'Latest smartphone with advanced features', 799.99, 'Electronics', 100),
    ('Coffee Mug', 'Ceramic coffee mug with company logo', 12.99, 'Office Supplies', 200),
    ('Desk Chair', 'Ergonomic office chair for comfort', 249.99, 'Furniture', 25),
    ('Notebook', 'Professional notebook for meetings', 8.99, 'Office Supplies', 150),
    ('Monitor', '27-inch 4K monitor for productivity', 399.99, 'Electronics', 30),
    ('Keyboard', 'Mechanical keyboard for typing', 129.99, 'Electronics', 75),
    ('Mouse', 'Wireless optical mouse', 49.99, 'Electronics', 120);

-- Insert sample orders
INSERT INTO orders (user_id, total_amount, status) VALUES
    (1, 1299.99, 'completed'),
    (2, 812.98, 'pending'),
    (3, 249.99, 'shipped'),
    (1, 58.98, 'completed'),
    (4, 1699.98, 'processing'),
    (5, 179.98, 'completed'),
    (2, 399.99, 'shipped');

-- Create some indexes for better query performance
CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_products_category ON products(category);
CREATE INDEX idx_orders_user_id ON orders(user_id);
CREATE INDEX idx_orders_status ON orders(status);
CREATE INDEX idx_orders_date ON orders(order_date);

-- Create a view for order summaries
CREATE VIEW order_summary AS
SELECT 
    o.id as order_id,
    u.username,
    u.email,
    o.total_amount,
    o.status,
    o.order_date
FROM orders o
JOIN users u ON o.user_id = u.id;

-- Add some comments to tables for documentation
COMMENT ON TABLE users IS 'User accounts and profile information';
COMMENT ON TABLE products IS 'Product catalog with pricing and inventory';
COMMENT ON TABLE orders IS 'Customer orders and transaction history';
COMMENT ON VIEW order_summary IS 'Consolidated view of orders with user information';

-- Grant permissions (if needed)
-- GRANT SELECT ON ALL TABLES IN SCHEMA public TO testuser;
-- GRANT SELECT ON ALL SEQUENCES IN SCHEMA public TO testuser;
