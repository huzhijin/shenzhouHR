# 宝塔升级包 20260901-session-idle（登录保持更久）

打开报表看一会儿再操作，容易被当成「服务连不上」。原因是 **空闲 30 分钟就掉登录**，不是服务器挂了。

本包改为：

- 没有操作：登录保持 **6 小时**
- 一直在用：最长 **12 小时**
- 页面开着时每 10 分钟自动续期
- 真过期时提示「登录已过期，请重新登录」

换 jar 和前端。不覆盖 `start-prod.sh`。不重算。

发布包：`release-candidates/shenzhouhr-release-20260901-session-idle.tar.gz`

## 本机上传

```bash
scp release-candidates/shenzhouhr-release-20260901-session-idle.tar.gz \
    release-candidates/upgrade-session-idle.sh \
    root@服务器:/root/
```

## 服务器

```bash
sha256sum /root/shenzhouhr-release-20260901-session-idle.tar.gz
# 必须是
# 9c25f09c1277542be1e9815c34732de4e7b704fc74c65ea1279272c1d8541cc1

chmod +x /root/upgrade-session-idle.sh
bash /root/upgrade-session-idle.sh
```

健康检查出现 `401` 后浏览器 **Ctrl+F5**。不要重算。
