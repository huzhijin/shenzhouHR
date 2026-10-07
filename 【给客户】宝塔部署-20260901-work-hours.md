# 宝塔升级包 20260901-work-hours（月度工时拆调休）

查询报表「月度工时」按人事口径：

- **请假小时** = 事假/病假及其他，不含年假、不含调休
- **年假小时** = 当月年假
- **加班换调休** = 当月加班转成调休额度
- **实际调休** = 当月请的调休
- **实际工时** = 应出勤 + 计薪加班 - 请假 - 年假 + 加班换调休 - 实际调休

换 jar 和前端。不覆盖 `start-prod.sh`。不重算。

发布包：`release-candidates/shenzhouhr-release-20260901-work-hours.tar.gz`

```bash
scp release-candidates/shenzhouhr-release-20260901-work-hours.tar.gz \
    release-candidates/upgrade-work-hours.sh \
    root@服务器:/root/

sha256sum /root/shenzhouhr-release-20260901-work-hours.tar.gz
# 必须是
# eccd81cae573aef6476101c0699dc16845a4a4a3c11b878ca3284f63dec72418

chmod +x /root/upgrade-work-hours.sh
bash /root/upgrade-work-hours.sh
```

健康检查出现 `401` 后浏览器 **Ctrl+F5**。不要重算。
