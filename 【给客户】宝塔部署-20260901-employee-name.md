# 宝塔升级包 20260901-employee-name（更正姓名）

编辑员工时，生效日填入职当天会被当成「数据已变化」。本包允许同一天更正姓名/工号，直接改当前版本。

报表按 `employee_version` 显示姓名，更正后刷新即可，**不用重算考勤**。

```bash
scp release-candidates/shenzhouhr-release-20260901-employee-name.tar.gz \
    release-candidates/upgrade-employee-name.sh \
    root@服务器:/root/
sha256sum /root/shenzhouhr-release-20260901-employee-name.tar.gz
# 2babed3be629c85675161a21eaee0fc1e191501bc255d117f9d71e810ddbfa20
chmod +x /root/upgrade-employee-name.sh
bash /root/upgrade-employee-name.sh
```

不覆盖 start-prod.sh。不要重算。Ctrl+F5。
