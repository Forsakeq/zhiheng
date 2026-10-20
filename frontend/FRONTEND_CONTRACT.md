# 知衡 App 前端协作约定

使用 uni-app / Vue 3 / TypeScript，App-Vue 为手机交付形态，H5 用于预览和联调。根端管理 package/vite/pages/manifest/App/main、公共组件、样式、client、session、ui、时间工具、今日页面。其他模块不得修改这些公共文件。

## 运行与数据来源

前端与同级 Java 后端联调，浏览器开发服务默认通过 `/api` 代理到 `http://127.0.0.1:8080`。数据库、后端与前端需要同时可用。用户内容统一来自后端接口；新账号空数据按空状态展示，接口错误按实际错误展示。页面不提供本地虚构数据、预设生成结果或独立体验入口。未登录时应先展示登录引导，不发起需要账号的读取请求。

## 公共契约（根端实现）

- `@/api/client`: `request<T>(path: string, options?: RequestOptions): Promise<T>`。path 为 `/api/v1/...`；options = `{method?: 'GET'|'POST'|'PUT'|'PATCH'|'DELETE', data?: unknown, query?: Record<string, unknown>, auth?: boolean, mutation?: boolean, idempotencyKey?: string, epoch?: boolean}`。自动解包 data；mutation=true 自动附 UUID 幂等键，epoch=true 附当前 epoch；401 串行刷新后仅重试一次，原幂等键保持不变；ApiError 有 status/code/message/fieldErrors；网络错误 code=NETWORK_ERROR。禁止捕获请求错误后返回虚构内容或成功状态。
- `@/stores/session`: `session` reactive 对象 `{tokens:AuthTokens|null, me:MeView|null, configuration:ClientConfiguration|null, apiBaseUrl:string, revision:number}`。提供 `applyTokens(tokens)`、`setMe(me)`、`clearSession()`、`setApiBaseUrl(url)`、`installationKey()`、`clientPlatform()`。token 仅保存在会话内存中，不把 refresh token 或健康原文写入明文持久缓存。`AuthTokens/MeView/...` 均在 `@/api/types`。通过 `computed(() => !!session.tokens)` 判断是否已登录。
- `@/stores/ui`: `ui` reactive `{activeTab:'today'|'plans'|'assistant'|'trends'|'profile', refreshTick:number}`，`goTab(tab)`、`navigate(url)`、`back()`、`refreshApp()`。普通子页面用 navigate；回主入口 goTab。
- `@/stores/app`: `refreshSession(): Promise<void>` 获取/me与config，`featureEnabled(code):boolean`。能力开关遵从服务端 features，AI 功能还需检查当前用途授权。
- `@/utils/time`: `today():string`(设备业务日 YYYY-MM-DD)、`dayOffset(n:number,from?:string):string`、`localTimeToIso(date:string,time:string):string`、`deviceTimeZone():string`、`formatTime(iso?:string|null):string`、`formatDay(date:string):string`、`formatDuration(minutes:number):string`、`weekDays(anchor?:string):{date:string,day:string,label:string,isToday:boolean}[]`、`uuid():string`。
- `@/utils/feedback`: `toast(message:string)`、`showError(error:unknown)`、`confirm(title:string,content:string):Promise<boolean>`，只显式 destructive 操作用 modal。
- `@/api/types`: 基础 `Id=string`, `PageResult<T>`, `MutationResult`, `TaskView`, `RecordView`, `UserGoalView`, `AuthTokens`, `MeView`, `ClientConfiguration`。域内复杂 DTO 自己模块定义并导出。

## 公共组件（根端实现，均自动easycom不必导入也可显式import）

- `AppIcon name="..." :size="20" color="#..."`，通用图标 home/calendar/sparkles/chart/user/arrow-right/arrow-left/chevron-down/plus/check/close/moon/sun/activity/utensils/heart/clock/bell/shield/settings/logout/refresh/search/book/download/trash/help/edit/filter/flag/cloud/info/send/more/check-circle/leaf/mail/lock/eye。
- `AppButton` props `variant='primary'|'secondary'|'ghost'|'danger'`, `size='normal'|'small'`, `loading:boolean`, `disabled:boolean`, `icon?:string`, `block:boolean`；emit click。
- `AppHeader` props `title:string`, `subtitle?:string`, `back:boolean=false`，slot right。带 back 的子页面 header 可自己调用 back。
- `StateView` props `kind='empty'|'error'|'loading'|'auth'`, `title`, `description`, `actionText?`；emit action。
- `BottomSheet` props `modelValue:boolean`, `title:string`, `subtitle?:string`；emit update:modelValue；slot默认/ footer。
- `TaskCard` props task:TaskView；emit complete(task), detail(task), skip(task)。
- `RecordCard` props record:RecordView；emit click(record)。

## 样式

全局通用类：page-stack / section-heading / eyebrow / card / muted / row / between / chip / pill / field / input / label / two-col / page-title / error-text / metric / divider / subtle-link。主色 #1B6554，背景 #F5F7F5，卡片白，圆角14px。正文15px，卡片内边距18px，页面边距20px。优先使用 view/text/button/input/textarea/picker/scroll-view 等 uni-app 组件，不用 DOM API 实现业务布局。不添加无实际动作按钮。

子页面根容器 `<view class="sub-page"><AppHeader ... back/><view class="page-stack">...</view></view>`。全局处理安全区、暗色、减少动态效果。页面不使用浏览器 alert/localStorage/window/history/router；uni.navigateTo 等通过上述工具。H5 特有行为需条件编译。

## 页面与分工

页面文件自定后报告路径，根端统一生成 pages.json。每个领域 API 严格以 backend/resource、controller、docs 为准，不照旧产品草案编接口。接口数据为空或请求失败时显示对应空状态或错误状态，不在前端填充内容。当前范围是用户App，不做管理员后台。

## 账号注册与本地验证码

- 默认后端使用 `local` profile、`DEV` 环境和 `CONSOLE` 验证码方式，不要求 SMTP。注册发送验证码仍调用真实接口，验证码由后端生成与验证。
- 用户点击获取验证码后，到运行 Java 后端的终端查找 `[LOCAL-VERIFICATION]`，将 `code=` 后的六位数字复制到前端。验证码不放入接口返回，不在浏览器控制台输出。
- 公共配置接口返回 `localDevelopment`、`authentication.registrationEnabled` 和 `authentication.verificationDelivery`；前端根据实际返回显示发送方式，不写死环境判断结果。
- `local + DEV` 环境可通过 `/api/v1/development/policies/service` 读取并渲染真实 JSON 服务说明，当前默认版本为 `local-service-v1`。用户仍需阅读并同意当前版本，确认已年满 18 周岁。
- 前端仅在后端明确声明本地环境、并返回约定的本地说明路径时接受该相对地址；其他环境使用已发布的 HTTPS 文档。不能把任意 HTTP 地址当成服务说明。
- 本地专用注册配置无需修改数据库种子中的注册开关；总开关 `ZH_REGISTRATION_ENABLED=false` 仍可关闭注册。非本地环境使用 SMTP 和正式注册配置，`prod` 禁止 CONSOLE。
- AI、通知与外部数据用途授权继续使用各自发布的说明和版本，本地服务说明不会替代其他用途授权。

详细启动步骤见 [VS Code 运行与 App 打包指南](VSCode运行与APP打包指南.md)，后端环境配置见 [后端 README](../backend/README.md)。
