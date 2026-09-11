-- Deli directory 2026-08-28: intern empnos were changed to official HR numbers.
-- 葛倩荣 SZSTSX50 -> SZST0498
-- 程兆俊 SZSTSX58 -> SZST0554
-- 蔡小钰 SZSTSX67 -> SZST0654
-- 赵建浩 SZSTSX71 -> SZST0680
-- Apply on the live DB after deploy, then run Deli sync + month recalc.
-- Do not call checkin_query_init.

SELECT employee_id, employee_number, display_name
FROM employee
WHERE employee_number IN (
    'SZST0498', 'SZST0554', 'SZST0654', 'SZST0680',
    'SZSTSX50', 'SZSTSX58', 'SZSTSX67', 'SZSTSX71');
