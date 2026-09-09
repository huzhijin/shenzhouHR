# 2026-09-09 前端浏览器兼容（360 空白页）

只换前端静态文件。不换 jar、不覆盖 `start-prod.sh`、不跑 Flyway、不重算。

发布包：`release-candidates/shenzhouhr-release-20260909-browser-compat.tar.gz`

升级脚本：`docs/deployment/2026-09-09-browser-compat-upgrade.sh`

## 改了什么

- Tabler SVG `size` 改为数字 `20` / `32`，不再写 `var(--size-icon-*)`（该属性不支持 CSS 变量，360 等浏览器会报 SVG width 错误并空白）。
- `index.html` 的 CSP meta 去掉 `frame-ancestors 'none'`（meta 里无效，只能靠 Nginx 响应头，现网 `deploy/baota/nginx` 已有）。

## 部署

把 tar.gz 和升级脚本传到 `/root` 后：

```bash
sha256sum /root/shenzhouhr-release-20260909-browser-compat.tar.gz
chmod +x /root/upgrade-browser-compat.sh
bash /root/upgrade-browser-compat.sh
```

浏览器 **Ctrl+F5**。用 360 打开登录页和侧栏，确认图标和页面不再空白。
