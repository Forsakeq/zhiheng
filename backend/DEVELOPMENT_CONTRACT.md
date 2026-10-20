# 知衡后端实现协作合同

2026-10-18，Java 21 / Spring Boot 3.3.13 / com.yqsoft。以已交付SQL为物理结构，API规划为对外合同。代码分层并正常换行，不用压缩一行类。根端维护pom、Starter、公共基础、所有实体/mapper生成；各模块不得各造并行基础。

## 公共类型（根端实现）

- `exception.BusinessException(int status, String code, String message)`；静态`badRequest(code,msg)`(422)、`conflict(code,msg)`、`notFound()`、`forbidden(code,msg)`、`unavailable(code,msg)`。
- `resource.common.ApiResponse<T>(String code, String message, T data, String requestId, Instant serverTime)`；`ApiResponse.ok(data)`；控制器可直接返回ApiResponse或ResponseEntity。202/201应由ResponseEntity设状态。
- `resource.common.PageResult<T>(List<T> items, String nextCursor, boolean hasMore, Integer totalApproximate)`；`PageResult.of(items,next,hasMore)`。列表可先limit+1，使用签名游标，禁止任意offset/排序SQL。
- `resource.common.MutationResult` record字段`String resourceType, String id, String rowVersion, String changeSeq, String operationId, boolean replayed, Map<String,Object> extra`；静态`of(String type,long id,long rowVersion)`；`withExtra(Map)`；JSON extra使用@JsonUnwrapped。
- `resource.common.Rows`静态：`map(Object... keyValuePairs)`（允许null）、`text(row,key)`、`number(row,key)`(long，null=0)、`integer(row,key)`、`bool(row,key)`、`instant(row,key)`(Instant/null)、`date(row,key)`(LocalDate/null)、`json(row,key)`(JsonNode/null)、`utc(Instant)`(LocalDateTime/null)。数据库行Map一律snake_case。
- `resource.common.ValidationSupport` Spring bean：`validate(T value)`返回同对象、违反DTO约束抛422；`convert(JsonNode,Class<T>)`先转类型再校验。字段未知422。各模块DTO自定义跨字段校验需业务再核验。
- `resource.common.RequestContext`静态：`requestId()`、`idempotencyKey()`(String UUID，缺失422)、`dataEpoch()`(Long/null)、`set(String requestId,String idempotencyKey,Long epoch)`、`clear()`；可`snapshot()`/`restore(State)`供离线批次临时头上下文。

## 身份（api_plan实现）

- `security.AuthenticatedUser(long userId,long sessionId,long installationId,String audience,Set<String> permissions)`；`security.AuthContext.current()`（必登录否则401）、`optional()`(Optional)、`userId()`(long)、`set(AuthenticatedUser)`、`clear()`。
- api_plan负责AuthInterceptor并通过其WebMvcConfigurer注册，不覆盖全局Jackson；PUB严格限定，后台ADMIN audience+权限码鉴权，手机不接受ADMIN。
- 权限注解建议`security.RequirePermission("content.read")`、`security.PublicEndpoint`；其精确签名由api_plan发全组。根端及AI控制器按该注解。

## 数据访问（根端实现）

`dao.SqlStore` Spring bean，所有SQL值参数绑定：

- `query(String sql,Object... args)`→List<Map<String,Object>>；`find(String sql,Object... args)`→Optional<Map<String,Object>>；`one(String sql,Object...args)`→Map，缺失404。
- `execute(String sql,Object...args)`→int；`count(String sql,Object...args)`→long。
- `insert(String table,Map<String,Object> values)`→long，自动补相应公共created_at/updated_at/row_version，允许显式值；表/字段名按schema白名单，禁止用户任意拼SQL。
- `update(String table,long id,long expectedRowVersion,Map<String,Object> values)`→long新版本，CM乐观锁更新；缺失/冲突409，调用前业务已核归属。自动updated_at/row_version+1。
- `owned(String table,long id,long userId)`、`ownedIncludingDeleted(...)`、`lockOwned(...)`→Map，缺失/跨用户404。前者排除软删。
- `metadata()`→SchemaMetadata，提供`hasColumn(table,column)`、`tables()`。
- JSON列写值用JsonNode或Map/List（DAO将ObjectMapper序列化成字符串），时刻使用Instant或UTC LocalDateTime；BLOB写byte[]。
- 不把查询原行直接响应前端，需明确View/白名单映射，hash/cipher/key_ref/凭据不可泄露。

## 业务事务/幂等（根端实现）

`persistence.MutationExecutor` Spring bean：

- `mutate(String operationCode,Object canonicalRequest,boolean requireEpoch,Supplier<MutationResult> action)`→MutationResult。user来自AuthContext，幂等键和epoch来自RequestContext，事务锁顺序clock→app_user→sync_operation→业务。核ACTIVE/fence（安全操作另走认证服务），HMAC摘要；相同键重放最小回执，异payload409。
- 可在action中调用`upsert(String entityType,long id,long rowVersion,Map<String,Object> publicProjection)`、`delete(String entityType,long id,long rowVersion)`，分配该用户seq；必须同事务。entity白名单来自正式12种。禁止同步cipher/token，敏感原文投影需限制。
- `outbox(String eventType,String aggregateType,long aggregateId,Long aggregateRowVersion,Map<String,Object> payload)`在当前事务写最小事件（自动capture epoch）。
- `currentEpoch()`、`currentSeq()`→long，仅当前事务内；`active()`→boolean；`assertConsent(String purpose)`→当前授权row（GRANTED/有效期核验）。
- 嵌套mutate复用外层事务/幂等键，避免AI apply再分第二操作。PlanService/RecordService请提供可被AI调用的公开typed方法，正常mutate将自动join。
- `offline`模块调用业务service前临时替换RequestContext幂等key/epoch；接口内部仍走同服务。各模块实现下面handler用于离线分派。
- `persistence.OfflineCommandHandler`：`Set<String> operations()`、`MutationResult execute(String operationCode,Long entityId,JsonNode payload)`。只注册规划允许的RECORD_*/TAG_*/TASK_*/GOAL_*/AVAILABILITY_*/EXCEPTION_*命令，入口convert并校验DTO。

## 加密/游标（根端实现）

- `service.common.CryptoService`：`encrypt(String)`→byte[]（null返回null）、`decrypt(Object)`→String（byte[]密文，null返回null）、`keyRef()`→String、`hmac(String purpose,String value)`→byte[32]、`hashToken(String)`→byte[32]、`randomToken()`→安全随机base64url串、`sha256(byte[])`→byte[]、`digest(Object)`→byte[32]规范化对象HMAC；密钥来自稳定本地私有文件或env，prod必须env。
- `service.common.CursorService`：`encode(String scope,Map<String,Object> state)`→String；`decode(String cursor,String scope)`→Map，绑定当前用户/作用域/HMAC；不存在返回空Map，伪造422。每个筛选集应纳入scope。
- `config.AppProperties`配置前缀`zhiheng`，详细配置稍后根端发；第三方依赖无配置一律503，不假成功。

## 其他约定

- API路径`/api/v1`、管理`/api/admin/v1`；P2显式503功能关闭，可保留真实DTO入口但不要返回已接入。
- 异步任务不得持数据库锁调用外部HTTP；回写前重新clock/app_user锁并核epoch/consent版本。根端提供`persistence.WorkerGuard`稍后约定。
- 全局Long/BigInteger输出字符串（计数请用Integer）；Instant统一Z毫秒；数据库事务UTC；DTO Id/Version请求使用String并显式严格正数解析工具`resource.common.Ids.parse(String)`→long，`Ids.optional(String)`→Long。
- 不能把异常SQL参数、token/邮箱/记录原文写日志。控制器异常封套由全局Advice统一。
- 各模块测试写自己包，根端负责统一MySQL集成验证与覆盖清单；不要运行全量Maven clean互相清输出。
