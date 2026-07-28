# WAVE-1 本机 MySQL 自动化底座

该目录只管理本机 `localhost|127.0.0.1:3306` 上两个精确库：

- `shenzhou_hr_dev`
- `shenzhou_hr_test`

脚本不使用 Docker、Podman、Testcontainers、Flyway `clean`，也没有删除、清空或重建
`shenzhou_hr_dev` 的代码路径。dev 只执行 create-if-missing 和 Flyway 前向迁移。

## 凭据文件

将 `wave1-local.env.example` 复制到仓库外的绝对路径，填入四个密码变量，再设置：

```bash
chmod 600 /absolute/outside/repository/shenzhouhr-wave1.env
```

解析器不 `source` 文件，不执行 shell 语法。每行格式为 `NAME=raw value`，值是第一个
`=` 后的原始单行文本。执行时只报告变量名的 `available/missing` 状态，不打印值。
脚本拒绝仓库内文件、符号链接、非当前用户所有文件和非 `0600` 权限。

固定账号：

- `root`：只用于本机管理控制面；
- `shenzhou_hr_local_migrator`：只对两个授权库拥有 Flyway 所需 DDL/DML；
- `shenzhou_hr_dev_app`：只对 dev 拥有 `SELECT/INSERT/UPDATE/DELETE`；
- `shenzhou_hr_test_app`：只对 test 拥有 `SELECT/INSERT/UPDATE/DELETE`。

密码只写入权限为 `0600` 的临时 MySQL/Flyway 配置文件，并在脚本退出时删除；不会成为
命令行参数。禁止通过 `bash -x` 或其他 shell tracing 运行。

## 默认 dry-run

```bash
bash deploy/mysql/wave1-local-mysql.sh
```

默认只打印范围和变量名，不读取环境文件、不连接数据库、不修改任何状态。

只读探测：

```bash
bash deploy/mysql/wave1-local-mysql.sh \
  --env-file /absolute/outside/repository/shenzhouhr-wave1.env \
  probe
```

## 显式执行

创建缺失数据库、收敛三个账号权限并执行 `SHOW GRANTS`：

```bash
bash deploy/mysql/wave1-local-mysql.sh \
  --env-file /absolute/outside/repository/shenzhouhr-wave1.env \
  --execute provision
```

执行全部 WAVE-1 本机数据库门：

```bash
bash deploy/mysql/wave1-local-mysql.sh \
  --env-file /absolute/outside/repository/shenzhouhr-wave1.env \
  --execute all
```

`all` 的顺序是：

1. 探测 server version、字符集、排序规则、SQL mode、时区和默认引擎；
2. create-if-missing 两库，创建/更新三个最小权限账号并核验 grants；
3. dev 只执行 `validate → migrate → validate`，不重建；
4. test 经破坏性预检后从空库 V1 迁移到最新并证明第二次 migrate 为 no-op；
5. test 再经破坏性预检，构造 V1/V2 checkpoint 后升级到最新，验证 V2 基线行保留；
6. 验证两个应用账号执行 DDL、GRANT 和访问对方 schema 全部失败；
7. 输出 `TEST_FINAL_STATE=PRESERVED|EMPTY|DROPPED`。

每次 test `DROP DATABASE` 前都会实时输出并核验：

- 连接目标只能是 `localhost` 或 `127.0.0.1`；
- 端口和服务端 `@@port` 都必须是 `3306`；
- server hostname 和 `VERSION()`；
- `DATABASE()` 必须为 `<none>`；
- 精确目标必须是 `shenzhou_hr_test`。

若本机没有 `utf8mb4_0900_ai_ci`，脚本明确失败，不会静默替换排序规则。当前 V1/V2
迁移本身使用该排序规则；兼容性差异必须先登记并通过更高版本前向方案处理，不能改写
V1/V2 checksum。

## Flyway

优先调用 PATH 中的 `flyway` CLI；未安装 CLI 时自动使用仓库内
`deploy/mysql/flyway-maven.sh`，它通过 Maven Wrapper 运行与后端一致的 Flyway
12.4 和 MySQL 数据库模块。也可在私有环境文件中通过
`SHENZHOUHR_FLYWAY_BIN` 指定单一可执行文件路径。正式迁移位置固定为：

`backend/src/main/resources/db/migration`

生成的 Flyway 配置固定：

- `baselineOnMigrate=false`
- `cleanDisabled=true`
- `createSchemas=false`
- UTC connection timezone
- 只允许精确 dev/test schema

可用子命令见：

```bash
bash deploy/mysql/wave1-local-mysql.sh --help
```

## WAVE-2 前向迁移验收

`wave2-local-mysql.sh` 保留 `shenzhou_hr_dev`，只重建
`shenzhou_hr_test` 内的表，并验证空测试库 V1 到最新版本及 V1～V4
基线到最新版本两条路径。它同时固定核验 V1～V4 源文件 SHA-256、WAVE-2
表/索引/外键/检查约束、`flyway validate`、第二次迁移 no-op，以及应用账号
CRUD 与 DDL、`GRANT`、跨库访问拒绝。

```bash
bash deploy/mysql/wave2-local-mysql.sh \
  --env-file /absolute/outside/repository/shenzhouhr-wave1.env \
  --execute all
```

WAVE-2 验收脚本只使用已经配置的最小权限迁移账号和应用账号，不需要 MySQL
root 账号，不删除数据库、不重置开发数据、不部署，也不连接生产。

## WAVE-3：隔离构建 MySQL 8.4.10

`mysql8410-isolated.sh` 只接受规格固定的官方 source tarball：

- 文件名：`mysql-8.4.10.tar.gz`
- URL：`https://cdn.mysql.com/Downloads/MySQL-8.4/mysql-8.4.10.tar.gz`
- SHA-256：`d57a6730baef14ae118f7f4a6e02845b5b50933758df61fb06e104f27ccc8f96`

默认命令是纯计划，不创建目录、不下载、不编译，也不启停进程：

```bash
bash deploy/mysql/mysql8410-isolated.sh
```

所有实际命令都必须同时给出 `--execute` 和新的 `--run-id`。推荐先执行只读安全
预检，并把证据写到一个明确的 W3 run 目录：

```bash
bash deploy/mysql/mysql8410-isolated.sh \
  --execute \
  --run-id w3-mysql8410-preflight-001 \
  --evidence-dir /Users/huzhijin/Downloads/shenzhouHR/docs/verification/wave3/runs/w3-mysql8410-preflight-001/mysql \
  preflight
```

完整构建、初始化、启动、精确版本查询和关闭：

```bash
bash deploy/mysql/mysql8410-isolated.sh \
  --execute \
  --run-id w3-mysql8410-build-001 \
  --evidence-dir /Users/huzhijin/Downloads/shenzhouHR/docs/verification/wave3/runs/w3-mysql8410-build-001/mysql \
  --jobs 4 \
  all
```

默认隔离根目录是
`/Users/huzhijin/.local/share/shenzhouhr/mysql-8.4.10-isolated`，其中 source、
build、install、data、socket、pid、log、配置和凭据各自独立；服务只绑定
`127.0.0.1:13306`。可用 `--root` 改成当前用户主目录下另一个不含空格的绝对
路径。

构建预检要求 CMake、Apple Clang、GNU Make、OpenSSL 3、ncurses，以及 Bison
不低于 3.0.4。macOS 自带的 Bison 2.3 会被明确拒绝；若安装了 Homebrew Bison，
脚本只把其绝对路径传给 CMake，不修改全局 `PATH`。

MySQL 8.4.10 的 Apple CMake 配置会传入较新版 ld64 才支持的
`-no_warn_duplicate_libraries`。在本机 macOS 12 / Apple ld64-820.1 上，构建只
通过 `mysql8410-ld-wrapper.sh` 删除这个无语义的 warning-suppression 参数，再
绝对调用 `/usr/bin/ld`；不会修改已校验的官方 source tree，也不会过滤其他 linker
参数。

本机 `/usr/local/include` 中另有 Homebrew Protobuf 和 Abseil。脚本显式选择
官方包自带的 Protobuf 24.4 与 Abseil 20230802.1。
`mysql8410-cxx-wrapper.sh` 只移除这两个 bundled 目录被上游 CMake 误分类出的
精确 `-isystem` 参数，再把同一路径作为普通 include 放到最前；这样 Apple Clang
不会先从 `/usr/local/include` 混入另一个 ABI/头文件版本。失败过的旧构建目录会
保留取证，修正后的构建使用独立的 `build-bundled-deps-v2`，不删除旧产物，也不
修改官方 source tree。

每个实际命令都会在动作前后独立采集现有 `/usr/local/mysql` 8.0.34 的 PID、
规范化版本、进程参数、3306 listener、`/tmp/mysql.sock` inode、datadir、
launchd label/state/plist SHA 和 mysqld SHA，并要求两份 TSV 字节完全相同。
同一 evidence 目录还会生成按命令命名的完整 transcript，构建输出不会只依赖终端
缓冲区。
脚本拒绝符号链接路径、13306/socket/PID 冲突、非精确 `SELECT VERSION()`
SemVer core `8.4.10` 以及任何非 `127.0.0.1` listener。

脚本不会修改 `/usr/local/mysql`、`/usr/local/mysql/data`、`/tmp/mysql.sock`、
3306、launchd、PATH 或系统 symlink，也没有删除、清库和重置路径。初始化时生成
的本机 root 凭据只保存在隔离根目录下 mode `0600` 的 client 配置中，不写仓库、
不放命令行、不输出到日志。独立实例需分步维护时可用 `build`、`init`、`start`、
`verify`、`stop`；每一步仍须使用新的 run ID/证据路径。

### 预览与 W3 数据库契约检查

只看将执行什么，不连接数据库：

```bash
bash deploy/mysql/wave3-local-mysql.sh plan
```

只检查 review-owned registry 与当前 V7 是否一致，也不连接数据库：

```bash
bash deploy/mysql/wave3-local-mysql.sh verify-static
```

该静态门固定 registry SHA-256
`aa82a8d941bdb02b0f206b8715691d5f048fb4cfa34e01fc488e4263241803cb`
和恰好 22 张 W3 表、71 个命名外键、25 个命名 CHECK、84 个显式索引；
`flyway_schema_history` 是外部迁移元数据，不计入这 22 张表。
运行态 `verify-contract` 只接受隔离绝对客户端、`127.0.0.1:13306` 与真实
`SELECT VERSION()` SemVer core `8.4.10`，并允许 V7 后存在 V8+ 迁移。
初始化脚本从真实 `@@server_uuid` 固定出
`mysql8410:<uuid>:shenzhou_hr_test`；provision 会把该非机密身份与连接参数、生成
凭据一起写入仓库外 mode `0600` 文件：

`/Users/huzhijin/.local/share/shenzhouhr/mysql-8.4.10-isolated/secrets/wave3-runtime.env`

只预览账号和环境文件的收敛计划：

```bash
bash deploy/mysql/mysql8410-provision-wave3.sh plan
```

服务启动后，实际 provision 与全量数据库门的调用形式为：

```bash
bash deploy/mysql/mysql8410-provision-wave3.sh \
  --execute --run-id w3-mysql8410-provision-001 provision

bash deploy/mysql/wave3-local-mysql.sh \
  --env-file /Users/huzhijin/.local/share/shenzhouhr/mysql-8.4.10-isolated/secrets/wave3-runtime.env \
  --execute --run-id w3-mysql8410-contract-001 \
  --confirm-test-table-rebuild shenzhou_hr_test:w3-mysql8410-contract-001 \
  all
```

两条迁移路径都先停在 V6，显式建立固定 baseline legal entity
`30000000-0000-0000-0000-000000000001`，再执行 V7；它不会在固定法人不存在时
偷偷选择其他法人。每个运行态 PASS marker 都携带同一个数据库身份，防止不同实例
或不同 test schema 的证据被拼接。

`upgrade-v6-test` 和 `all` 会重建 `shenzhou_hr_test` 的表，因此除了
`--execute`/`--run-id` 还必须显式传入
`--confirm-test-table-rebuild shenzhou_hr_test:<同一个run-id>`；未给出精确令牌
时脚本会在加载环境、连接数据库之前停止。
