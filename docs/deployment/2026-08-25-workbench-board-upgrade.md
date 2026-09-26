# 考勤工作台昨日看板日期对齐 20260825-workbench-board

把 `shenzhouhr-release-20260825-workbench-board.tar.gz` 和 `upgrade-workbench-board.sh` 都传到 `/root` 后执行：

```bash
bash /root/upgrade-workbench-board.sh
```

不要覆盖 `/opt/shenzhouhr/start-prod.sh`。部署后浏览器 Ctrl+F5 打开 `/workbench`。这次不需要重算 OPEN 月。
