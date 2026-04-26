-- ============================================================
-- MySQL Schema + Sample Data
-- ============================================================

CREATE DATABASE IF NOT EXISTS userdb;
USE userdb;

CREATE TABLE IF NOT EXISTS users (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100)  NOT NULL,
    email       VARCHAR(150)  NOT NULL UNIQUE,
    department  VARCHAR(50),
    job_title   VARCHAR(100),
    is_active   BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at  DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    INDEX idx_email      (email),
    INDEX idx_department (department)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Sample data
INSERT INTO users (name, email, department, job_title, is_active) VALUES
    ('Alice Johnson',  'alice@example.com',   'Engineering',  'Senior Engineer',      true),
    ('Bob Smith',      'bob@example.com',     'Engineering',  'Junior Engineer',       true),
    ('Carol Williams', 'carol@example.com',   'Product',      'Product Manager',       true),
    ('David Brown',    'david@example.com',   'Design',       'UX Designer',           true),
    ('Eve Davis',      'eve@example.com',     'Engineering',  'Staff Engineer',        true),
    ('Frank Miller',   'frank@example.com',   'Sales',        'Account Executive',     true),
    ('Grace Wilson',   'grace@example.com',   'HR',           'HR Manager',            true),
    ('Henry Moore',    'henry@example.com',   'Finance',      'Financial Analyst',     true),
    ('Iris Taylor',    'iris@example.com',    'Engineering',  'DevOps Engineer',       true),
    ('Jack Anderson',  'jack@example.com',    'Marketing',    'Marketing Lead',        false);

-- Create app user with restricted privileges
CREATE USER IF NOT EXISTS 'appuser'@'%' IDENTIFIED BY 'apppass';
GRANT SELECT, INSERT, UPDATE, DELETE ON userdb.* TO 'appuser'@'%';
FLUSH PRIVILEGES;
