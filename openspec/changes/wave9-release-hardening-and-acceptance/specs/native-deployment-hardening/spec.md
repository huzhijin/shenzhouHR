## ADDED Requirements

### Requirement: 原生部署必须最小权限且不可变
生产模板 MUST 面向 Ubuntu Server 24.04 LTS、OpenJDK 21、Nginx、systemd 和 MySQL 8.4 LTS；应用以无登录非 root 账号运行，artifact 只读，运行目录最小可写，Flyway 迁移账号与应用账号分离。

#### Scenario: systemd 单元权限过宽
- **WHEN** 单元以 root 运行、允许任意文件系统写入、缺少提权防护或从可写 artifact 启动
- **THEN** 部署合同测试 MUST 失败

### Requirement: Nginx 必须强制安全传输和数据防缓存
Nginx 配置 SHALL 强制 HTTPS、HSTS、CSP、点击劫持防护、内容嗅探防护、严格 referrer/permissions policy、请求关联标识、API no-store、敏感请求大小/速率限制，并使用不含 query string 的访问日志。

#### Scenario: API 或 HTML 可被共享缓存
- **WHEN** 配置允许 API、SPA HTML 或错误响应进入共享/持久缓存
- **THEN** Nginx 合同测试 MUST 失败

#### Scenario: 哈希静态资产请求
- **WHEN** 客户端请求已构建的版本化 `/assets/` 文件
- **THEN** Nginx SHALL 只对该资源返回 immutable 长缓存且找不到时不得回退到 SPA HTML

### Requirement: 发布预检不得自行切流
预检工具 MUST 只读校验 artifact SHA、文件权限、环境变量名、开发入口、Nginx/systemd 语法、Flyway 计划和健康检查，并在缺少必需证据时拒绝进入切流步骤。

#### Scenario: 在本 worktree 运行预检
- **WHEN** 操作者未提供显式受控部署环境
- **THEN** 工具 SHALL 仅输出计划/静态结果，不安装服务、不修改系统目录且不连接生产

### Requirement: 回滚必须保留前向数据库语义
应用和前端 SHALL 通过不可变版本指针回滚；数据库 MUST 使用前向修复迁移，不得自动执行 Flyway clean、逆向迁移或删除业务数据。

#### Scenario: 候选迁移与上一应用不兼容
- **WHEN** 切流前兼容检查无法证明上一应用可读取迁移后 schema
- **THEN** 发布 MUST 在切流前停止并保持上一版本服务
