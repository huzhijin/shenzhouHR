# 宝塔覆盖包 20260901-menu-order（只改侧栏）

只覆盖前端。**不换 jar、不停 Java、不重算、不改数据、不改 cron。**

侧栏变成：

1. 报表中心（考勤工作台、考勤报表）
2. 查询报表
3. 组织与人员
4. 考勤设置
5. 数据接入
6. 系统管理

发布包：`release-candidates/shenzhouhr-web-20260901-menu-order.tar.gz`

## 1. 本机上传

```bash
scp release-candidates/shenzhouhr-web-20260901-menu-order.tar.gz \
    release-candidates/upgrade-menu-order.sh \
    root@192.168.160.226:/root/
```

## 2. 服务器覆盖

```bash
sha256sum /root/shenzhouhr-web-20260901-menu-order.tar.gz
# 必须是
# 6dc01b19d6e738cf9f6316d918a1c6bc0770df16fdee736672cbe22f3fe43f35

chmod +x /root/upgrade-menu-order.sh
bash /root/upgrade-menu-order.sh
```

浏览器 **Ctrl+F5**。旧前端备份在 `/opt/shenzhouhr/backups/web-*-before-menu-order-*.tar.gz`。
