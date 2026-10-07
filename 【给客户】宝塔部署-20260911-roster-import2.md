# 宝塔升级包 20260911-roster-import2

在上一包花名册导入之上：预检不再把同名组别（`RF-F组`、`测试组`）误判成换上级；界面阻断/新增/修改显示中文。

换 jar 和前端。不覆盖 `start-prod.sh`。不要回拨 OA 水位。不要改 cron。Excel 不用换。

发布包：`release-candidates/shenzhouhr-release-20260911-roster-import2.tar.gz`

SHA256：`e26c7ab43571eea98c68a8715bd4732ad34eed75561d3b7e66c8fa902a04bf15`

## 1. 本机上传

```bash
scp /Users/huzhijin/Downloads/shenzhouHR/release-candidates/shenzhouhr-release-20260911-roster-import2.tar.gz \
    /Users/huzhijin/Downloads/shenzhouHR/release-candidates/upgrade-roster-import2.sh \
    root@服务器:/root/
```

## 2. 服务器

```bash
sha256sum /root/shenzhouhr-release-20260911-roster-import2.tar.gz
# 必须是
# e26c7ab43571eea98c68a8715bd4732ad34eed75561d3b7e66c8fa902a04bf15

chmod +x /root/upgrade-roster-import2.sh
bash /root/upgrade-roster-import2.sh
```

健康检查出现 `401` 后浏览器 **Ctrl+F5**。

## 3. 再预检一次

还是上传 `/Users/huzhijin/Downloads/september-2026-hires.xlsx`（或服务器 `/root/september-2026-hires.xlsx`）。

- 不应再出现「RF-F组 / RF-G组 / 测试组 已存在但上级不同」
- 陈倩 `SZST0731` 可能仍是「修改」（工号已在库），可以发布
- 无阻断后再确认发布
