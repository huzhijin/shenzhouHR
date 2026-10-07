#!/bin/bash
# 年假汇总数字实际是小时，导入时按天×8。只改年假开口，调休和 8 月 OA 小时不动。
set -euo pipefail
SQL="$(cd -- "$(dirname -- "$0")" && pwd)/fix-annual-leave-excel-hours-not-days.sql"
mysql -h127.0.0.1 -P3306 -uroot --password='e0e17f3df673a9f8' \
  --default-character-set=utf8mb4 --table shenzhou_hr < "$SQL"
echo "=== annual leave unit fix done ==="
