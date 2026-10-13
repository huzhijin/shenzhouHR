# 宝塔升级包 20260825-deli-matrix

合并两段现网改动：

- 本会话：得力全员雪花认人 + 历史回放（修 HTTP 500）
- 会话 `01a03707-d670-7bd0-8a10-5d3b06ff4079`：8:30 即迟到、休息日两头打卡、加班色按段、年假悬停小时、Excel 签到/签退两行

发布包：`release-candidates/shenzhouhr-release-20260825-deli-matrix.tar.gz`

SHA256：`5c196c558a95e41cdad9f85be72cfc70a932ea0d4717fef884e2767cdc2b4352`

两个文件都传到 `/root` 后（**不用再输账号密码**）：

```bash
chmod +x /root/upgrade-deli-matrix.sh
nohup bash /root/upgrade-deli-matrix.sh >> /root/upgrade-deli-matrix.log 2>&1 &
tail -f /root/upgrade-deli-matrix.log
```

`Ctrl+C` 只停 tail。回放可能 10–40 分钟。成功应出现回放 HTTP 200 和「升级脚本结束」。

只要换文件、先不回放：

```bash
SKIP_REPLAY=1 bash /root/upgrade-deli-matrix.sh
```

## 这一行会做什么

1. 备份并替换 jar，同步前端到 `/www/wwwroot/192.168.160.226`
2. 临时打开得力身份回放（不改 `start-prod.sh`，不改 00:00/12:00 cron）
3. 按得力人员目录×花名册给全员写雪花绑定
4. 回放 8/1 起到今天
5. 重算江苏神州 2026-08（矩阵迟到/加班色也靠这次重算）
6. 按原启动脚本再拉起，回放关掉
7. 有 Excel 则自动对账

不要只点「重新计算」。关账月不会自动重开。

## 现网路径不要改

- jar：`/opt/shenzhouhr/app/shenzhou-hr.jar`
- 前端：`/www/wwwroot/192.168.160.226`
- 启动：`/opt/shenzhouhr/start-prod.sh`

## 验收

浏览器 **Ctrl+F5**：

- 彭伟 / 陆玉蕾 / 李扬 8/1–8/12 表有卡不得全空
- 08:30 打卡显示迟到；08:29 不准时标迟到
- 周六两头打卡两行都有时间；有加班单的段涂加班色，上午迟到不被加班绿盖掉
- 导出考勤明细：每人签到、签退两行，两格可两色
- 年假悬停小时跟班次走，不写死 5
