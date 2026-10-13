# 2026-10-13 正式交付源码合并与回滚

此目录保存最后上线版本 `shenzhouhr-release-20261013004730` 的70文件增量：44个既有文件更新、26个新增文件。来源提交为 `4fe99a6803853b8971faa93647667824d9bba9ca`。

`forward.patch` 用于从合并前源码应用本次更新；`rollback.patch` 恢复这70个文件到合并前状态，保留此前已交付的其他考勤修复。文件指纹见 `manifest.json`。已在临时副本实际应用更新、回滚，并验证44个文件原样恢复、26个新增文件移除。

在项目根目录回滚，先检查后应用：

```sh
git apply --check docs/maintenance/20261013-source-sync/rollback.patch
git apply docs/maintenance/20261013-source-sync/rollback.patch
```

只在检查成功后执行第二行；如果之后修改过同一文件，应先保存后续改动并解决冲突。需要让GitHub也回滚时，把回滚形成的源码差异正常提交并推送，不要重写分支历史。

重新应用本次更新：

```sh
git apply --check docs/maintenance/20261013-source-sync/forward.patch
git apply docs/maintenance/20261013-source-sync/forward.patch
```

补丁仅改变源码，不会逆转服务器部署、数据库V79迁移、考勤补算或OA来源处理。

## 合入后验证

2026-10-13：正式源码清单1,913文件逐一匹配；后端200项测试、前端80项测试、部署脚本14项测试通过，前端类型检查通过。详细结果见 `validation.json`。
