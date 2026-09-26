# 宝塔部署指南 - Full Calculation Engine 版本

> **已废弃（2026-08-17）**：本文仅保留历史记录，不得用于本次部署。请使用
> docs/deployment/baota-deployment-guide.md 和候选包内说明；所有凭据必须从
> 客户服务器的受控环境文件或密码库注入。

## 📦 部署包信息

**生成时间**: 2026-08-14 12:44:58
**版本**: 0.1.0-SNAPSHOT (Full Calculation Engine 集成版本)
**包位置**: `backend/target/shenzhou-hr-0.1.0-SNAPSHOT.jar`
**包大小**: 约 60-80 MB

## 🔧 环境变量配置（重要）

### 必须配置的环境变量

宝塔面板 → Java 项目 → 你的项目 → 设置 → 环境变量，添加以下配置：

```bash
# ========== HR 主数据库配置 ==========
SHENZHOUHR_DB_URL=jdbc:mysql://localhost:3306/shenzhou_hr_prod?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true
SHENZHOUHR_DB_USERNAME=shenzhou_hr_app
SHENZHOUHR_DB_PASSWORD=你的HR数据库密码

# ========== OA 数据源配置（新增）==========
OA_MYSQL_JDBC_URL=jdbc:mysql://<OA_HOST>:<OA_PORT>/szoa?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
OA_MYSQL_USERNAME=<客户提供的只读账号>
OA_MYSQL_PASSWORD=<从受控密码库获取>

# ========== 基础配置 ==========
SHENZHOUHR_FLYWAY_ENABLED=false
SHENZHOUHR_BOOTSTRAP_ENABLED=false
SPRING_PROFILES_ACTIVE=prod

# ========== 时区配置 ==========
TZ=Asia/Shanghai

# ========== JVM 配置 ==========
JAVA_OPTS=-Xms512m -Xmx2048m -XX:+UseG1GC
```

### 配置说明

#### OA 数据源配置
- **地址**: `192.168.2.169:3308` （客户内网）
- **库名**: `szoa`
- **账号**: `kaoqin2026`
- **密码**: 从受控密码库获取，不得写入命令或文档

⚠️ **重要**：
1. 确保宝塔服务器能访问 `192.168.2.169:3308`
2. 可以用以下命令测试连通性：
```bash
mysql --defaults-extra-file=/root/.oa-readonly.cnf -e "SELECT 1;"
```

#### HR 主数据库
- 通常是本地 MySQL（localhost:3306）
- 库名通常是 `shenzhou_hr_prod` 或 `shenzhou_hr`
- 需要客户提供实际的账号密码

## 📋 部署步骤

### 第一步：备份当前版本

```bash
# SSH 登录到宝塔服务器
cd /www/wwwroot/你的项目目录/

# 备份当前 jar 文件
cp shenzhou-hr.jar shenzhou-hr.jar.backup-$(date +%Y%m%d-%H%M%S)

# 备份日志（可选）
cp -r logs logs.backup-$(date +%Y%m%d-%H%M%S)
```

### 第二步：停止当前服务

在宝塔面板：
1. 网站 → Java项目
2. 找到"神州HR"项目
3. 点击"停止"按钮
4. 等待状态变为"已停止"

或通过命令行：
```bash
# 查找进程
ps aux | grep shenzhou-hr

# 停止进程（替换 PID）
kill -15 <PID>

# 确认已停止
ps aux | grep shenzhou-hr
```

### 第三步：上传新 jar 文件

**方式 A：宝塔面板上传**
1. 文件 → 你的项目目录
2. 点击"上传"
3. 选择 `backend/target/shenzhou-hr-0.1.0-SNAPSHOT.jar`
4. 上传完成后重命名为 `shenzhou-hr.jar`

**方式 B：SCP 上传**
```bash
# 在你本地电脑执行
scp backend/target/shenzhou-hr-0.1.0-SNAPSHOT.jar \
    root@客户服务器IP:/www/wwwroot/项目目录/shenzhou-hr.jar
```

**方式 C：使用宝塔远程下载**
1. 先把 jar 上传到一个临时服务器（如果你有）
2. 在宝塔文件管理器使用"远程下载"功能

### 第四步：配置环境变量

宝塔面板 → Java项目 → 你的项目 → 设置 → 环境变量

**复制上面"必须配置的环境变量"部分的所有内容**，并：
1. 修改 `SHENZHOUHR_DB_PASSWORD` 为实际的 HR 数据库密码
2. 确认 `SHENZHOUHR_DB_URL` 中的库名正确
3. ⚠️ **不要修改 OA 数据源配置**（已经是正确的）
4. 点击"保存"

### 第五步：检查数据库连通性

```bash
# 测试 HR 主数据库
mysql -h localhost -u shenzhou_hr_app -p shenzhou_hr_prod -e "SELECT 1;"

# 测试 OA 数据库（重要！）
mysql --defaults-extra-file=/root/.oa-readonly.cnf -e "SELECT 1;"
```

**如果 OA 数据库连接失败**：
- 检查防火墙是否允许 3308 端口
- 检查 192.168.2.169 服务器是否在线
- 联系客户网络管理员确认网络可达性

### 第六步：启动服务

在宝塔面板：
1. Java项目 → 你的项目
2. 点击"启动"按钮
3. 等待状态变为"运行中"

或通过命令行：
```bash
cd /www/wwwroot/你的项目目录/

# 启动（后台运行）
nohup java -jar shenzhou-hr.jar > logs/app.log 2>&1 &

# 查看进程
ps aux | grep shenzhou-hr
```

### 第七步：检查启动日志

```bash
# 实时查看日志
tail -f logs/app.log

# 或在宝塔面板查看
# Java项目 → 你的项目 → 查看日志
```

**检查关键信息**：
```
✅ Started ShenzhouHrApplication in X.XXX seconds
✅ Tomcat started on port(s): 8080
✅ No errors in log
```

**如果看到错误**：
- `Connection refused` → 数据库连接失败，检查地址和密码
- `Access denied` → 数据库账号密码错误
- `Unknown database` → 数据库名称错误
- `Communications link failure` → 网络不通，特别是 OA 数据库

### 第八步：验证服务

#### 健康检查
```bash
# 在宝塔服务器执行
curl http://localhost:8080/actuator/health

# 应该返回：
{"status":"UP"}
```

#### 检查前端
访问客户的前端地址（例如 `http://客户域名.com`）：
1. 能正常打开登录页面
2. 能成功登录
3. 菜单正常显示

#### 生成测试报表
1. 登录系统
2. 进入报表页面
3. 选择一个月份
4. 点击"生成报表"
5. 等待 10-30 秒
6. 检查报表是否生成成功

**预期结果**：
- ✅ 报表生成成功
- ✅ 有员工数据
- ✅ 打卡时间显示正确
- ⚠️ 迟到/早退时长可能为 0（已知问题）

## 🔍 验证新功能已启用

查看后端日志，搜索关键字：
```bash
grep "FullCalculationEngineOrchestrator" logs/app.log
grep "FULL_CALCULATION_V1" logs/app.log
```

如果看到这些日志，说明新的完整计算引擎已经启用。

## ⚠️ 已知问题与限制

### 1. 迟到/早退时长显示为 0
- **原因**：此功能尚未实现
- **影响**：报表中这两列数据不准确
- **规避**：前端可以暂时隐藏这两列

### 2. 班次标签显示"计算班次"
- **原因**：尚未从排班数据提取真实班次名称
- **影响**：可读性差一些
- **规避**：通过员工号和日期识别

### 3. 性能可能变慢
- **原因**：新算法更复杂（完整的排班匹配）
- **影响**：报表生成时间可能增加 2-5 倍
- **当前预期**：单公司单月 < 30 秒

### 4. OA 数据源要求
- **必须能连接** `192.168.2.169:3308`
- 如果 OA 服务器宕机或网络不通，会影响考勤计算
- 但不会导致系统崩溃（会跳过 OA 数据）

## 🔄 回滚方案（如果遇到问题）

### 快速回滚（5 分钟内）

```bash
# 1. 停止服务
# 在宝塔面板停止 Java 项目

# 2. 恢复备份
cd /www/wwwroot/你的项目目录/
ls -lt shenzhou-hr.jar.backup-*  # 查看备份文件
cp shenzhou-hr.jar shenzhou-hr.jar.failed-new-version
cp shenzhou-hr.jar.backup-YYYYMMDD-HHMMSS shenzhou-hr.jar

# 3. 启动服务
# 在宝塔面板启动 Java 项目

# 4. 验证
curl http://localhost:8080/actuator/health
```

## 📊 监控建议

部署后前 3 天，每天检查：
- 报表生成成功率
- 报表生成时长（是否 < 30 秒）
- 错误日志数量
- 用户反馈

如果发现：
- 报表经常失败 → 联系开发排查
- 报表超过 1 分钟 → 可能需要数据库索引优化
- 大量 OA 连接错误 → 检查网络或 OA 数据库状态

## 📞 故障排查

### 问题 1：启动失败，日志显示 "Access denied for user 'kaoqin2026'"

**原因**：OA 数据库账号密码错误或权限不足

**解决**：
```bash
# 测试 OA 数据库连接
mysql --defaults-extra-file=/root/.oa-readonly.cnf -e "SHOW TABLES;"

# 如果失败，联系客户确认账号密码
# 可能需要客户在 OA 数据库服务器上执行：
# 账号授权由客户 DBA 执行；应用只接受最小 SELECT 权限账号，不在文档中保存口令。
```

### 问题 2：报表生成超时或失败

**检查日志**：
```bash
grep "ERROR" logs/app.log | tail -50
grep "FullCalculationEngineOrchestrator" logs/app.log | tail -20
```

**可能原因**：
- 排班数据缺失 → 检查 `shift_segment` 表
- OA 数据查询慢 → 检查 192.168.2.169:3308 网络延迟
- 内存不足 → 增加 JVM 内存（修改 JAVA_OPTS）

### 问题 3：前端显示"系统错误"

**检查**：
1. 后端是否在运行：`ps aux | grep shenzhou-hr`
2. 健康检查：`curl http://localhost:8080/actuator/health`
3. 查看错误日志：`tail -100 logs/app.log`

## ✅ 部署成功标志

- [x] 服务启动成功（日志显示 "Started ShenzhouHrApplication"）
- [x] 健康检查返回 UP
- [x] 前端能正常打开
- [x] 能登录系统
- [x] 能生成报表（即使迟到/早退为 0）
- [x] 后端日志出现 "FullCalculationEngineOrchestrator"

## 📝 部署记录模板

```
部署日期：2026-08-14
部署人员：___________
服务器地址：___________
备份文件：shenzhou-hr.jar.backup-___________
启动时间：___________
健康检查：□ 通过  □ 失败
测试报表：□ 通过  □ 失败
用户验收：□ 通过  □ 失败

备注：
___________________________________________
___________________________________________
```

---

**文档版本**: 1.0  
**创建日期**: 2026-08-14  
**包版本**: 0.1.0-SNAPSHOT (Full Calculation Engine)  
**OA 数据源**: 192.168.2.169:3308/szoa
