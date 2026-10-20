# 知衡健康助手后端

基于上传模板实现的 Java 后端，沿用 `com.yqsoft` 包名。使用 Java 21、Spring Boot 3.3.13、MySQL、MyBatis-Plus 和 JDBC，配合 Vue / uni-app 等手机前端。业务 SQL 与 `../database/zhiheng_full.sql` 的 60 张表保持一致。

## 已实现模块

| 模块 | 主要行为 |
| --- | --- |
| 账号与权限 | 邮箱验证码、注册、密码登录、刷新令牌轮换、会话撤销、后台独立登录域、RBAC |
| 个人档案 | 档案、偏好、目标字典、用户目标、周可用时间、指定日期例外、分用途授权 |
| 生活记录 | 睡眠、活动、饮食、精力四类事实，详情校验、标签、附件、软删与恢复 |
| 计划与任务 | 草稿版本、任务快照、冲突预览、版本激活、还原、固定任务、循环规则、执行与事实关联 |
| 智能助手 | 对话、异步运行、SSE 状态事件、结构化草稿、人工确认后应用、受控知识引用 |
| 统计与回顾 | 真实记录统计、数据覆盖、缺失值、时间重叠处理、周回顾证据、统计重算任务 |
| 提醒与反馈 | 提醒意图、站内通知、本地提醒登记与取消协议、反馈与后台处理 |
| 私有文件 | 上传意图、限时签名上传、大小和摘要校验、图片解码、加密存储、限时下载、删除 |
| 多设备同步 | 12 类实体初始化、增量序号、离线命令逐项回执、幂等重放、数据代际隔离 |
| 数据控制 | 冻结范围预览、重新认证确认、JSON / CSV ZIP 导出、部分删除、清空健康数据、注销与独立删除台账 |
| 后台管理 | 用户安全、角色权限、配置 JSON Schema 校验、最小审计、作业摘要、受限重试、知识内容编辑审核发布 |

手表配对、外部健康平台授权导入、语音转写和课表识别属于规划中的 P2 扩展；保留入口并明确返回 `503 FEATURE_NOT_ENABLED`。手机系统提醒登记不代表真实送达，当前没有可信平台送达回调适配器。

App 注册需要真实、可阅读的服务说明。本地 `local + DEV` 环境提供实际的 JSON 说明端点，供前端显示并由用户确认；正式环境使用 `ZH_SERVICE_POLICY_VERSION` 配合真实的 `ZH_SERVICE_POLICY_URL` HTTPS 文档。新增用途授权则使用 `ZH_CONSENT_POLICY_VERSION`，配合 `ZH_AI_POLICY_URL`、`ZH_NOTIFICATION_POLICY_URL`、`ZH_EXTERNAL_HEALTH_POLICY_URL` 分别提供 AI、通知、外部数据说明；这些用途文档仍默认留空，缺失时不能新增授权，已有授权仍可撤回。发布或修订说明后应同步更新版本，App 以服务端返回的当前版本提交确认。

## 目录结构

```text
src/main/java/com/yqsoft/
├── config/          配置、JSON 序列化、跨域与请求上下文
├── controller/      按业务模块划分的 HTTP 入口
├── dao/             参数绑定 SQL 与统一持久化访问
├── exception/       业务异常和全局错误封套
├── interceptor/     会话、audience 与权限检查
├── listener/        受控首位管理员初始化
├── mapper/          60 张表对应的 MyBatis-Plus Mapper
├── persistence/     事务幂等、同步投影、后台作业授权守卫
├── po/              60 张表对应的持久化实体
├── resource/        分模块请求 DTO、响应 View 与公共契约
├── security/        当前身份与权限注解
├── service/         业务规则、异步作业与外部适配器
└── Starter.java
```

复杂业务通过 `SqlStore` 进行参数化 SQL，便于明确控制锁顺序、关联约束和版本。PO 与 Mapper 保留作为表映射层；不会把 PO 或数据库原行直接返回给前端。日期、时区、版本、权限等规则放在 service，controller 只处理 HTTP 契约。

## 本地启动

需要 JDK 21、Maven 3.9，以及已初始化的 MySQL 8.0.29 或更高版本。完整建库脚本已在 MySQL 8.0.29 独立实例验证；脚本包含 MySQL 8 的 CHECK 和 JSON Schema 约束，不支持换成 MySQL 5.7 或用 H2 代替业务数据库。

1. 在空库环境导入 `../database/zhiheng_full.sql`。脚本内指定 `zhiheng` 库；运行前阅读脚本头部说明，已有业务库应走迁移。
2. 为应用创建数据库账号，并配置下面的环境变量。示例中的密码由你们填写。
3. 在 `backend` 目录编译并启动。

```powershell
$env:ZH_DB_URL = 'jdbc:mysql://127.0.0.1:3306/zhiheng?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
$env:ZH_DB_USERNAME = 'zhiheng'
$env:ZH_DB_PASSWORD = '填写你自己的数据库密码'

mvn -s .mvn/settings.xml clean package
java -jar target/zhiheng-backend-1.0-SNAPSHOT.jar
```

健康检查：`GET http://127.0.0.1:8080/api/v1/health`。启动时不自动执行建库 SQL，也不自动创建测试账号。

### 本地注册：从 Java 控制台复制验证码

默认启动使用 `local` profile、`DEV` 环境，无需配置 SMTP。前端注册时填写邮箱并获取验证码，在启动后端的 Java 控制台寻找以下格式，把 `code=` 后的六位数字复制到前端：

```text
[LOCAL-VERIFICATION] purpose=REGISTER destination=你填写的邮箱 code=六位验证码（仅本地开发）
```

这里使用真实随机验证码，数据库保存验证码摘要、有效期、重试次数和消费状态；接口不返回验证码。重发间隔、小时发送次数、错误次数和单次消费限制保持有效。账号、档案和后续业务数据真实写入当前配置的 MySQL，并非前端临时账号。

默认本地配置已开启注册总开关、`CONSOLE` 发送方式和本地专用的注册开关替代，因此无需手动修改 `app_configuration` 的种子值；替代逻辑只在 `local` profile 与 `DEV` 同时成立时适用，且不会改写数据库开关。显式设置 `ZH_REGISTRATION_ENABLED=false` 仍会禁止注册。前端配置接口的 `authentication.registrationEnabled` 与注册接口复用相同判定。

前端会读取 `/api/v1/development/policies/service` 的真实本地服务说明，要求确认已阅读、同意当前版本以及年满18周岁。本地说明使用版本 `local-service-v1`，只用于开发联调；正式发布前团队须提供实际主体、联系方式和数据处理规则。该说明端点在非 `local + DEV` 环境返回404。

### 正式邮件、政策版本与注册

需要真实邮件时设置 `ZH_VERIFICATION_DELIVERY=SMTP`，原有 SMTP 能力保留。未配置邮件时返回 `503 MAIL_NOT_CONFIGURED`。生产环境拒绝 `CONSOLE`，即使错误组合 `local,prod` profile 也不能启用；生产必须发布真实 HTTPS 服务说明，并同时开启部署注册开关与数据库注册配置。

```powershell
$env:ZH_MAIL_HOST = '你们的SMTP主机'
$env:ZH_MAIL_PORT = '587'
$env:ZH_MAIL_USERNAME = '发件账号'
$env:ZH_MAIL_PASSWORD = 'SMTP授权码'
$env:ZH_MAIL_FROM = '发件邮箱'
$env:ZH_VERIFICATION_DELIVERY = 'SMTP'
$env:ZH_REGISTRATION_ENABLED = 'true'
$env:ZH_SERVICE_POLICY_VERSION = '你们已展示给用户的服务政策版本'
$env:ZH_SERVICE_POLICY_URL = '你们已发布服务说明的真实HTTPS地址'
$env:ZH_CONSENT_POLICY_VERSION = '你们已展示给用户的分用途授权说明版本'
```

在未使用本地专用替代的环境中，受控初始化当前环境的注册项。以下为 DEV 示例，正式环境应按实际环境匹配：

```sql
UPDATE app_configuration
SET value_json = JSON_OBJECT('booleanValue', TRUE),
    row_version = row_version + 1,
    updated_at = UTC_TIMESTAMP(3)
WHERE environment = 'DEV' AND config_key = 'auth.registration_enabled';
```

不要用占位政策版本上线。前端提交的 `policyVersion` 必须与实际展示及服务端配置一致；授权目的分别确认。

### 首位管理员

先通过正常注册创建账号，取得 `userId`。受控部署时设置 `ZH_BOOTSTRAP_ADMIN_USER_ID` 后重启，初始化器只允许建立首位有效系统管理员；已存在其他管理员时拒绝借此接管。

```powershell
$env:ZH_BOOTSTRAP_ADMIN_USER_ID = '已注册用户的userId'
# 如该首位负责人需要初始化其他业务角色，在首次启动时显式列出：
$env:ZH_BOOTSTRAP_ADDITIONAL_ROLE_CODES = 'CONTENT_EDITOR,CONTENT_REVIEWER,CONTENT_PUBLISHER,SUPPORT,OPERATIONS'
java -jar target/zhiheng-backend-1.0-SNAPSHOT.jar
```

完成后移除这两个引导变量并重新登录。后台登录使用 `clientAudience=ADMIN`、`platform=WEB` 和新的安装标识；手机令牌不能进入后台。首位管理员只默认获得 `SYSTEM_ADMIN`，其他角色须明确引导或由已有足够权限者授予。内容作者不能审核自己编辑的版本，实际演示需准备不同编辑和审核账号。

后台 `POST /api/v1/auth/login` 请求示例：

```json
{
  "account": "填写已注册的管理员邮箱",
  "password": "填写该账号密码",
  "installationKey": "填写管理浏览器生成并保存的UUID",
  "clientName": "知衡管理台",
  "clientAudience": "ADMIN",
  "platform": "WEB"
}
```

### AI 服务

提供真实的 OpenAI 兼容 `chat/completions` 适配器；供应商不限于 OpenAI。地址可填写兼容服务的版本根路径或完整 `/chat/completions` 路径。

```powershell
$env:ZH_AI_ENABLED = 'true'
$env:ZH_AI_BASE_URL = '填写服务商HTTPS兼容接口地址'
$env:ZH_AI_API_KEY = '填写服务商密钥'
$env:ZH_AI_MODEL = '填写模型名称'
```

需要用户有效 `AI_PROCESSING` 授权。缺少配置返回 `503 AI_NOT_CONFIGURED`，不生成模拟结果。模型仅提出待确认草稿；记录和计划最终应用仍经过同一套业务校验、归属与版本核验。默认未接入任何真实付费账号，测试使用本机 HTTP 协议夹具验证适配器。

模型环境变量之外，还需由管理员在当前环境受控开启实际准备演示的配置项：`feature.ai_plan`、`feature.ai_record`、`feature.ai_chat`、`feature.weekly_review`，值采用 `{"booleanValue":true}`。种子默认全部关闭；前端按公开配置显示入口。P2 的语音、课表和手表功能仍保持关闭，不能通过开放 AI 标识自动接入。

### 文件、密钥和部署

| 环境变量 | 含义 / 默认值 |
| --- | --- |
| `SERVER_PORT` | HTTP 端口，默认 `8080` |
| `ZH_ENVIRONMENT` | 配置环境 `DEV` / `TEST` / `PROD`，默认 `DEV` |
| `ZH_STORAGE_ROOT` | 私有加密文件目录，默认 `.local/files` |
| `ZH_PUBLIC_BASE_URL` | 前端能访问的后端完整根地址，默认 `http://127.0.0.1:8080` |
| `ZH_KEY_FILE` | 本地稳定密钥文件，默认 `.local/zhiheng.keys` |
| `ZH_ENCRYPTION_KEY` | Base64 编码的 32 字节 AES 密钥 |
| `ZH_HMAC_KEY` | Base64 编码、至少 32 字节的独立 HMAC 密钥 |
| `ZH_REQUIRE_EXTERNAL_KEYS` | 要求外部密钥，默认 `false`；prod 配置强制开启 |
| `ZH_DELETION_LEDGER` | 独立删除台账文件，默认 `.local/privacy-ledger.jsonl` |
| `ZH_JOBS_ENABLED` | 是否运行异步队列与调度，默认 `true` |
| `ZH_FILE_SCANNER_COMMAND` | 可选外部内容检查器的可执行文件路径 |

本地首次启动生成稳定私有密钥文件，后续启动复用。已有加密数据必须使用原密钥；备份、迁移时一起管理，不能随意删除或重新生成。生产使用 `--spring.profiles.active=prod`，通过环境注入独立密钥、实际 HTTPS 根地址、数据库与邮件凭据，并配置前端跨域来源。实际手机调试时 `ZH_PUBLIC_BASE_URL` 应改为手机可访问的局域网地址。

PNG/JPEG 会实际解码并核验尺寸。其他允许的文件类型需要配置外部检查器，否则拒绝核验完成；检查器采用 ClamAV `clamscan` 兼容调用：`可执行文件 --no-summary <临时解密文件>`，退出码 `0` 表示通过、`1` 表示拒绝、其他值表示检查器不可用，15 秒超时。临时文件在调用后清除。

独立删除台账应部署到与业务数据库备份隔离的受控持久卷；其中保留最小 ID / 代际 / 范围摘要，不含健康正文。当前没有自动恢复业务备份的程序，恢复流程须先应用此台账再开放服务。

## 前端联调约定

- 手机接口前缀 `/api/v1`；后台接口前缀 `/api/admin/v1`。原规划的 `/admin/*` 相对路径映射到后台前缀。
- 身份头：`Authorization: Bearer <accessToken>`。首次安装报告真实 `platform`；注册安装 ID 与业务同步安装 ID 使用服务端返回值。
- 业务写入通常需要 `Idempotency-Key: <UUID>`；涉及健康数据的写入还需要 `X-Data-Epoch: <datasetEpoch>`。认证刷新使用单次消费协议，不混用业务幂等键。
- ID、序号、版本等 64 位整数响应为十进制字符串；请求也传字符串，避免 JavaScript 精度损失。计数为普通数字。
- 更新/删除传 `expectedRowVersion`；发生 `409` 先重新读取，不用新幂等键盲目覆盖。
- 同一用户、同一个幂等键、同一请求重放最小回执；同键换请求内容返回冲突。上传 token、注销查询 token 等敏感一次性凭据只在首次响应出现，客户端必须及时安全保存。
- 所有时刻传带 `Z` 的 UTC 时间，业务日期另传 `recordLocalDate` / 时区及偏移。跨午夜时间窗用 `endDayOffset=1`。
- 列表使用签名游标；游标绑定用户和筛选条件，不能跨账号/筛选复用。
- 统一响应包含 `code/message/data/requestId/serverTime`；错误额外包含 `error`，不能只依赖 HTTP 200 或展示原始异常。

接口模块清单、典型请求与实现状态见 [接口实现清单](docs/接口实现清单.md)、[联调示例](docs/联调示例.md) 和 [账号联调说明](docs/账号联调说明.md)。DTO 的最终字段以 `resource` 下 Java 类型为准。

## 验证

```powershell
# 默认只运行不依赖外部服务的测试；MySQL 条件测试明确跳过。
mvn -s .mvn/settings.xml test

# 完整 SQL 导入一次性专用测试库后启用真实集成测试。
$env:ZH_TEST_DB_URL = 'jdbc:mysql://127.0.0.1:3306/zhiheng?serverTimezone=UTC'
$env:ZH_TEST_DB_USERNAME = '专用测试库账号'
$env:ZH_TEST_DB_PASSWORD = '专用测试库密码'
mvn -s .mvn/settings.xml test
```

完整流程测试会真实注册账号、写记录和文件、执行数据删除，因此测试库必须可丢弃；部分用例用事务回滚，跨事务作业用例保留测试数据直到测试库整体清理。不会调用真实模型或发送真实验证码邮件。最终执行结果见 [验证记录](docs/验证记录.md)。

## 实现边界

目前交付的是可编译运行的服务端，不包含 Vue 页面、手机系统通知 SDK 或真实华为平台凭据。知识库初始为空，通过后台创建来源和内容、审核发布后才进入检索引用。P2 扩展需另写平台适配器再开启；不能只改配置把保留接口当成已完成集成。

同步与幂等记录当前保留到显式隐私清理，未启用按时间自动压缩；这样不会在尚未实现保留水位调度时误删初始化租约所需日志。长期部署可按规划另补可观测的保留期维护作业。
