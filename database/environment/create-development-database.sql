-- Purpose: create this project's independent, empty MySQL development database.
-- Related record: docs/开发环境配置.md. Business tables belong to later reviewed migrations.
-- Execute once after checking the server and confirming this schema name is unused.
-- Deliberately omit IF NOT EXISTS so an existing database cannot be silently reused.
CREATE DATABASE `municipal_access_ooad_dev`
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

-- Read back the actual schema settings and confirm that no tables were introduced.
SELECT schema_name AS db_name,
       default_character_set_name AS charset_name,
       default_collation_name AS collation_name,
       (SELECT COUNT(*)
          FROM information_schema.tables
         WHERE table_schema = 'municipal_access_ooad_dev') AS table_count
  FROM information_schema.schemata
 WHERE schema_name = 'municipal_access_ooad_dev';
