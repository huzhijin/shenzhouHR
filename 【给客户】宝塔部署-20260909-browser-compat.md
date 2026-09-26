# 宝塔升级包 20260909-browser-compat（360 空白页）

**只换前端。** 不换 jar，不覆盖 `start-prod.sh`，不改库，不重算，不改 OA/得力 cron。

修复：

- 侧栏/人员页 SVG 图标在 360 等浏览器因 `size="var(--size-icon-…)"` 无法渲染导致整页空白
- `index.html` CSP meta 去掉无效的 `frame-ancestors`（该指令只能走 Nginx 头，现网配置不用动）

发布包：`shenzhouhr-release-20260909-browser-compat.tar.gz`

SHA256：`f0d295429ab6bfcd560081ecaae7d4cab08450e6fd79fcae97f6371ed3df9f66`

## 1. 上传

把这两个文件传到服务器 `/root/`：

- `shenzhouhr-release-20260909-browser-compat.tar.gz`
- `upgrade-browser-compat.sh`（即仓库里的 `docs/deployment/2026-09-09-browser-compat-upgrade.sh`）

## 2. 服务器执行

宝塔 **终端**：

```bash
sha256sum /root/shenzhouhr-release-20260909-browser-compat.tar.gz
# 必须是
# f0d295429ab6bfcd560081ecaae7d4cab08450e6fd79fcae97f6371ed3df9f66
chmod +x /root/upgrade-browser-compat.sh
bash /root/upgrade-browser-compat.sh
```

## 3. 验收

浏览器 **Ctrl+F5**（建议 360 和 Chrome 各开一次）：

- 登录页能出来，不是白屏
- 登录后侧栏图标可见
- 人员 / 组织页能打开

前端目录仍是 `/www/wwwroot/192.168.160.226`。旧前端备份在 `/opt/shenzhouhr/backups/web-192.168.160.226-before-browser-compat-*.tar.gz`。
