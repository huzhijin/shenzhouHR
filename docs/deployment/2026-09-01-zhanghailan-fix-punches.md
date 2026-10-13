# 张海兰：只修 8 月打卡，先不重算

花名册 `SZST0303` 张海兰，`SZST0302` 姜长波。  
得力现网工号已是 0303。8 月若按旧号 0302 入库，只点「重新计算」搬不过来。

本脚本：

- 先查本库 8 月卡挂在谁名下
- 再拉得力，只认 `member_name=张海兰` 的卡
- 把这些卡挂到 `SZST0303`
- **不**重算、**不**跑全员 `run-deli-identity.sh`、**不**改 cron、**不**动姜长波

你刚才第二节 SQL 没出表，是日期被终端插进了控制符（`20[118;1:3u26`）。不要再手改那条 SQL，用脚本重查。

## 宝塔

把仓库里的 `scripts/fix-zhanghailan-august-punches.py` 拷到 `/root/`。

```bash
export MYSQL_PWD='e0e17f3df673a9f8'

# 1) 预览（只读）
python3 /root/fix-zhanghailan-august-punches.py

# 2) 确认 MOVE/ACTIVATE 没有姜长波的时刻后，才写库
APPLY=1 python3 /root/fix-zhanghailan-august-punches.py
```

第一次会拉得力 CHECKIN/KQ，可能几分钟，结果缓存在 `/root/zhanghailan-deli-cache.json`。重拉加 `REFRESH=1`。

只看本库、不拉得力：

```bash
SKIP_DELI=1 python3 /root/fix-zhanghailan-august-punches.py
```

`SKIP_DELI=1` 不能 APPLY。0302 上混着两个人的卡，必须用得力姓名区分。

## 怎么读预览

| 动作 | 含义 |
|---|---|
| `OK` | 已经在张海兰名下 |
| `MOVE` | 本库 raw 挂在姜长波，按姓名搬到 0303 |
| `ACTIVATE` | 已入库但隔离，挂到 0303 |
| `MOVE_ORPHAN` | 姜长波该时刻有卡、得力该时刻是张海兰，且姜长波得力没有同时刻卡 |
| `NEED_SYNC` | 得力有卡，本库还没有 raw。先页面手动同步得力，再跑脚本 |

页面手动同步：数据接入 → 考勤机数据 → 手动同步，等到成功。**不要点重新计算。**

## 写完之后

库里 `SZST0303` 8 月有效卡数应开始有数。  
查询报表格子仍是旧 pin，**先不要重算**。等 15:00 OA 整点成功后再用原来的 `recalculate-open-month.sh`。
