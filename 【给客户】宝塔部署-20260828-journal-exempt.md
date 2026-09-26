# 宝塔升级包 20260828-journal-exempt

考勤日报不再列出免打卡人员（高管角色 EXECUTIVE、员工常设免打卡）。

发布包：`release-candidates/shenzhouhr-release-20260828-journal-exempt.tar.gz`

SHA256：`8bc46dbfa2375450e64bb7e947b6b36387474bdcefa1af5ffa20201aaedf75a3`

两个文件都传到 `/root` 后，宝塔终端只跑一行：

```bash
bash /root/upgrade-journal-exempt.sh
```

文件：

- `release-candidates/shenzhouhr-release-20260828-journal-exempt.tar.gz`
- `release-candidates/upgrade-journal-exempt.sh`

## 这次会改什么

- 查询报表「考勤日报」及该页导出：不显示免打卡人员（例如董事长陈觉晓）
- 加班日报不变
- 无新 Flyway，不必为这次改动重算整月

## 现网路径不要改

- jar：`/opt/shenzhouhr/app/shenzhou-hr.jar`
- 前端：`/www/wwwroot/192.168.160.226`
- 启动：`/opt/shenzhouhr/start-prod.sh`
