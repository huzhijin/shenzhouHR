# 宝塔升级包 20260824-deli-empno

发布包：`release-candidates/shenzhouhr-release-20260824-deli-empno.tar.gz`

SHA256：`bd56d99d7651794c7ecb373dbd38e0037ae447961a62e404b2e42f6099b3d792`

两个文件都传到 `/root` 后，宝塔终端只跑一行：

```bash
bash /root/upgrade-deli-empno.sh
```

文件：

- `release-candidates/shenzhouhr-release-20260824-deli-empno.tar.gz`
- `release-candidates/upgrade-deli-empno.sh`

这一行会：换 jar → 清 8 月得力打卡 → 从 8/1 按天同步到今天（每天成功后再隔 20 秒）→ 自动重新计算 2026-08。

只要部署、先不重拉：

```bash
SKIP_RELOAD=1 bash /root/upgrade-deli-empno.sh
bash /root/reload-deli-august-daily.sh preview
bash /root/reload-deli-august-daily.sh
```

## 这次会改什么

- 得力打卡只认打卡条上的工号（`empno` / `employee_num`）
- 不再用人员目录 `id` 去对打卡 `user_id`（目录 387 是彭伟，打卡 387 实际是周步新）
- 前端不用换

## 现网路径不要改

- jar：`/opt/shenzhouhr/app/shenzhou-hr.jar`
- 启动：`/opt/shenzhouhr/start-prod.sh`

不要覆盖启动脚本。

## 验收

浏览器强刷 `考勤报表` 2026-08：

- 彭伟 8/4、8/5 **不应再是** 08:39 / 18:18（那是周步新）
- 这两张卡应到 **周步新 SZJN0002**
- 姚志淼不应再吃杨彬尉的卡；向佳文不应再吃袁萌的卡
