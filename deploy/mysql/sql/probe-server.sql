SET SESSION time_zone = '+00:00';

SELECT 'server_version' AS metric, VERSION() AS value
UNION ALL
SELECT 'server_hostname', @@hostname
UNION ALL
SELECT 'server_port', CAST(@@port AS CHAR)
UNION ALL
SELECT 'current_database', COALESCE(DATABASE(), '<none>')
UNION ALL
SELECT 'character_set_server', @@character_set_server
UNION ALL
SELECT 'collation_server', @@collation_server
UNION ALL
SELECT 'sql_mode', @@sql_mode
UNION ALL
SELECT 'session_time_zone', @@session.time_zone
UNION ALL
SELECT 'system_time_zone', @@system_time_zone
UNION ALL
SELECT 'default_storage_engine', @@default_storage_engine;

SELECT
    'utf8mb4_0900_ai_ci_available' AS metric,
    CASE WHEN COUNT(*) = 1 THEN 'YES' ELSE 'NO' END AS value
FROM information_schema.COLLATIONS
WHERE COLLATION_NAME = 'utf8mb4_0900_ai_ci';
