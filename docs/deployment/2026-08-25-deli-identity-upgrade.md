# 得力身份回放 20260825-deli-identity

发布包：`release-candidates/shenzhouhr-release-20260825-deli-identity.tar.gz`

SHA256：`dd36a18fd3518bf8fd259a43901c52bb46f97e1e43558f72a27413378952537c`

两个文件都传到 `/root` 后：

```bash
bash /root/upgrade-deli-identity.sh
```

不要覆盖 `/opt/shenzhouhr/start-prod.sh`。不要改得力 00:00/12:00 cron。不要只点重新计算。
