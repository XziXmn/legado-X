# 通用三级章节评论能力实施计划

## 0. 实施状态（2026-07-22）

代码实现和自动化门禁已完成，当前阶段为真机验收：

- 已实现来源无关的 v1 协议、能力探测、独立缓存、相邻章摘要预加载、锚点解析、真实分页投影、段级 overlay、页级下拉、章末 block 和滚动模式菜单入口。
- 已实现 `SOURCE_SCOPED` 动作弹窗：动作与摘要规则同源、认证源 DNS 固定、跨源只允许公网 HTTPS 且剥离凭据、系统 TLS 校验、重定向逐跳校验、固定 `0.78` 高度。
- 已设置独立应用身份：Release `io.legado.app.legadohub`，Debug `io.legado.app.legadohub.debug`，应用名“小说聚合阅读”。
- 已完成 LegadoHub 首个适配：增强客户端纯正文；普通客户端只追加章末兼容入口；评论数据继续由 Catalog 和插件体系提供。
- 自动验证结果：Legado-E `test`、`lint`、`assembleAppDebug`、`assembleAppRelease` 均通过；LegadoHub 定向测试 `24 passed`，完整门禁 `383 passed, 5 skipped`，22 个插件校验和前端 `70 passed` 均通过。
- 未完成项仅为需要设备的 `connectedAppDebugAndroidTest` 与本文第 7 阶段真机矩阵；它们由人工测试承担，不阻断测试版 APK 交付，但阻断正式发布结论。

## 1. 目标

在 Legado-E fork 中实现可被任意书源声明的段级、页级和章末评论能力，并用 LegadoHub 起点评论链路完成首个端到端适配。

交付后应满足：

- 分页阅读模式可通过向下拖动打开当前页热评；
- 书源可选择在段落末尾提供段级评论入口；
- 当前页热评由真实分页后的段落集合决定；
- 章末显示统一入口；
- 普通客户端不显示页热评，只保留章末入口；
- 当前章和相邻预加载章节都有自己的评论摘要；
- 所有评论数据仍经过书源或其后端插件体系；
- 客户端代码对具体站点和后端零感知。

## 2. 非目标

- 不在客户端实现起点私有 API、签名、解密或登录。
- 不新增绕过插件的评论接口。
- 不实现发评论、点赞、删除或审核。
- 不把完整评论列表原生化；v1 仍通过来源 Web 页面展示。
- 不修改普通 Legado 客户端代码。
- 不在 v1 劫持连续滚动模式的竖向导航。

## 3. 当前基线

### 3.1 Fork

- 仓库根目录：当前 `legado-X` checkout
- 分支：`codex/legadohub-reader`
- 上游：`https://github.com/Luoyacheng/legado-E.git`
- 基准提交：`21855a7bf901becfd1caba5cf30a3c84fd1533e1`

### 3.2 已确认接入点

- `BookSource.ruleContent` 已作为 JSON 存入数据库，适合增加嵌套规则。
- `ReadBook.contentLoadFinish` 同时处理当前章和相邻章预加载。
- `TextLine.paragraphNum` 和 `TextPage.lines` 可以提供真实页段落集合。
- `ReadView.onTouchEvent` 是所有翻页模式的统一触摸入口。
- `BottomWebViewDialog` 已支持固定高度、预注入 JS 与书源上下文。
- `BookSource.lastUpdateTime` 只用于导入更新判断，不会自动失效章节正文缓存。
- 旧 `ruleReview` 的转换器固定读写 `null`，不具备可复用实现。

### 3.3 起点交互证据

- 下拉阈值为 `52dp`。
- 达到阈值后只触觉反馈一次。
- 松手后页面回弹，再打开当前页热评。
- 传入的是当前页章节 ID 和段落编号集合。
- 购买页、无当前页和未达阈值不触发。

方向判断的部分反编译代码不完整。因此本项目把“手指向下、竖向占优”作为明确产品定义，不把无法证明的变量名猜测写进实现。

## 4. 模块划分

建议新增以下来源无关模块：

```text
data/entities/rule/
  ChapterCommentRule.kt

model/chapterComment/
  ChapterCommentPayload.kt
  ChapterCommentParser.kt
  ChapterCommentLoader.kt
  ChapterCommentCache.kt
  ChapterAnchorResolver.kt
  ChapterCommentRepository.kt
  ChapterCommentActionExecutor.kt

ui/book/read/comment/
  SegmentCommentOverlay.kt
  PageCommentProjection.kt
  PageCommentPullController.kt
  ChapterCommentBlock.kt
```

责任边界：

- `Rule`：只描述书源如何获取、规范化和打开三级评论内容。
- `Parser`：只做版本、类型和上限校验。
- `Loader`：只执行书源规则和网络请求。
- `Cache`：只保存规范化摘要，不保存完整评论。
- `Resolver`：只把来源锚点映射到正文段落。
- `Projection`：只把已解析锚点映射到当前 `TextPage`。
- `PullController`：纯手势状态机，不访问网络或 Activity。
- `ActionExecutor`：只验证和执行标准动作。
- `ReadView`：编排，不解析载荷、不拼 URL。

## 5. 分阶段实施

这是大型任务。按阶段集中测试，不要求每写一小段代码就运行完整测试；每阶段结束运行该阶段门禁，最终提交前再跑完整验证。

### 阶段 0：基线和命名

任务：

1. 保留现有上游 remote；只有用户提供或确认 fork 远端后才新增 `origin` 或推送。
2. 在修改根 Gradle 配置前确认应用展示名和独立 `applicationId`，保证能与普通版并存。
3. 不在能力类名中使用产品或站点名称。
4. 用 JDK 17 和可用 Android SDK 执行 `:app:tasks --all`，记录真实 variant 和测试任务。
5. 把现有 CI 中已经不存在的 `google` flavor 记为独立维护问题；未经确认不把 CI/root build 重构混进本功能。

验收：

- `git status` 只包含预期文件；
- 能列出有效 Gradle variant；若本机仍缺 Android SDK，本阶段明确标为阻塞而不是猜任务名；
- 普通版与定制版的安装包身份边界写入发布文档。

说明：应用品牌可以是定制的，能力协议必须保持通用。

### 阶段 1：协议模型与能力探测

任务：

1. 新增 `ChapterCommentRule` 并挂到 `ContentRule.chapterComment`。
2. 新增段级载荷、页级聚合配置、章末信息、显示描述和动作 DTO。
3. 新增严格 parser 和资源上限。
4. 在公共 `io.legado.app.help.JsExtensions` 增加 `hasReaderCapability(name, minVersion)`；`AnalyzeRule : JsExtensions` 自动向所有书源规则 JS 注入，不能只加在 Activity/WebView 扩展。
5. 更新 JS 帮助文档和书源 JSON 示例。

测试：

- 旧书源 JSON 导入结果不变；
- 新字段可导入、导出和 Parcelable 往返；
- 未知字段、未知版本、负数、超长 ID、过多锚点、超大载荷被拒绝；
- 未知显示预设回退到该层级默认值，来源不能注入任意原生布局；
- 未知能力返回 `false`；
- 用真实 `AnalyzeRule.getString()` 执行能力探测规则，证明 `ruleContent` 上下文同步可见；
- 不产生数据库 migration。

阶段门禁：

```powershell
.\gradlew.bat :app:testAppDebugUnitTest --no-daemon
```

如果实际任务名不同，先通过 `:app:tasks --all` 确认，不猜测通过。

### 阶段 2：加载、缓存和预加载

任务：

1. 用 `AnalyzeUrl` 执行 `url`，继承当前书源认证上下文。
2. 用 `AnalyzeRule` 执行 `data`，输出标准载荷。
3. 正文读取与摘要读取并行但互不阻塞。
4. 对当前章、上一章和下一章建立优先级队列。
5. 请求合并、超时、取消和 stale-while-revalidate。
6. 缓存键加入规则内容指纹和协议版本。
7. 为正文缓存增加轻量 sidecar，保存 `ruleContent` 指纹；无 sidecar 或指纹变化时重抓当前章和预加载窗口。

测试：

- 正文缓存命中时仍可加载评论摘要；
- 相邻章节不会错误复用当前章地址；
- 快速翻章取消旧请求，旧响应不能覆盖新章；
- 同章并发只发送一次网络请求；
- 401、超时、无网、格式错误不影响正文；
- 书源规则更新只失效相关评论缓存；
- 旧正文无 sidecar 时在线重抓、离线标记 stale 并在恢复网络后重抓；
- 不预加载完整评论列表和回复。

阶段门禁：单元测试加一个 MockWebServer 集成组。

### 阶段 3：段落锚点与真实分页投影

任务：

1. 在 `ContentProcessor` 输出后建立规范化段落索引。
2. 实现提示位置校验、局部模糊匹配、唯一全局匹配和歧义丢弃。
3. 把零基 `paragraphIndex` 显式转换为一基 `paragraphNum = paragraphIndex + 1`，不改正文字符串。
4. 从 `TextPage.lines` 提取当前页段落集合。
5. 聚合当前页段落 ID和评论总数，生成页级评论上下文。
6. 字号、行距、页边距和旋转后重新投影，不重新请求。
7. 页级集合只使用来源标记为 `pageEligible` 的可见段落，客户端不猜“热门”语义。

测试数据至少包含两个虚拟书源，证明实现不依赖单一字段名或 URL：

- 书源 A：直接返回标准 JSON；
- 书源 B：通过 JS 把另一种响应转换为标准 JSON。

测试场景：

- 完全匹配；
- 章节前置文字导致索引整体偏移；
- 聚合正文合并或拆分段落；
- 用户替换规则改变标点或空白；
- 重复段落导致歧义；
- 一个锚点跨两页；
- 一页包含多个锚点；
- 页内无锚点；
- 空正文页、消息页、购买页和 block-only page 安全返回空集合，不调用 `TextPage.paragraphs`；
- 第一个正文段落严格映射 `0 -> 1`；
- `paragraphCount` 正确展开连续段落范围；
- 跨页段落在每个实际显示页可用；
- 双页模式按手势起点所在半页聚合，无法定位时关闭手势。

阶段门禁：纯 JVM resolver/projection 测试全部通过。

### 阶段 4：三级原生入口和下拉状态机

任务：

1. 让 `ContentTextView` 持有 `SegmentCommentOverlay`：使用 `TextLine` 坐标和现有 `relativeOffset` 绘制，并在 `ContentTextView.click()` 的正文图片点击之前命中；`setContent`、滚动和页面回收时同步更新或清空。
2. 扩展 `TextPage.blocks` 与 `TextChapterLayout`：测量原生 `ChapterCommentBlock`，空间不足时创建 block-only page，重新生成的 `pages.size` 就是有效 `pageSize`。
3. 为 block-only page 定义安全的章节末尾位置，禁止访问空 `textLines.first()`；`TextPageFactory` 必须能正常进入和离开该页。
4. 把下拉判定实现成与 View 无关的 `PageCommentPullController`。
5. 在 `ReadView` 增加 `TouchOwner` 和单一 delegate 转发函数；`ACTION_DOWN` 建立候选并保留 delegate 现有 `DOWN/onDown`。
6. 第一次超过 touch slop 的 `MOVE` 先分类；竖向占优后用合成 `ACTION_CANCEL` 终止 delegate，再把 owner 切到评论。
7. `ACTION_UP/CANCEL` 按 owner 路由；取得所有权后不再向 delegate 转发当前序列。
8. 取消长按，系统取消或多点触控时完整复位。
9. 拖动使用三分之一阻尼，最大位移为视图高度四分之一。
10. `52dp` 进入 armed，仅触觉反馈一次。
11. 松手回弹后触发页级评论事件。
12. 阅读菜单增加滚动模式的“本页热评”降级动作。

状态机至少包含：

```text
Idle -> Tracking -> Pulling -> Armed -> Settling -> Opening -> Idle
                    |          |
                    +-> Cancel +-> Cancel
```

测试：

- `51dp` 不打开，`52dp` 进入 armed；
- 段级入口命中测试优先于普通单击翻页，但不抢占长按选文；
- 段级入口关闭时不创建 overlay 或点击区域；
- 章末 block 不进入正文、复制、搜索、导出或朗读；
- 评论摘要晚于正文到达时，章末 block 重排必须保持当前章节字符位置，不把读者强制跳到其他页；
- 末页空间不足时新增 block-only page，`pageSize`、下一页和上一页均可访问；
- `SegmentCommentOverlay` 在普通分页、连续滚动、相邻页预绘制和双页坐标中命中一致；
- 横向占优不抢占翻页；
- 上滑不触发；
- armed 后回拉到阈值内会取消；
- 每次手势最多打开一次、反馈一次；
- 无锚点、选文、多点、动画、自动翻页、朗读、错误页均不触发；
- `ACTION_CANCEL` 必须恢复所有 View 位移和 delegate 状态；
- delegate 收到一次且仅一次合成 `ACTION_CANCEL`，已取消序列不得中途恢复；
- owner 为评论时，`ACTION_UP` 不再读取 `pageDelegate.isMoved` 决定路由；
- 多点触控在取得所有权前保持旧行为，取得所有权后取消并吞掉剩余序列；
- 滚动模式不劫持竖向滚动。

阶段门禁：状态机 JVM 测试加关键 View instrumentation 测试。

### 阶段 5：三级评论动作与固定高度弹窗

任务：

1. 验证动作结构、URL scheme、来源和长度。
2. 支持 `segment`、`page`、`chapter` 三种事件，客户端只回传来源不透明 ID和动作数据。
3. 通过 `AnalyzeUrl` 获取首屏最终 URL、HTML 和来源认证 Header，首屏不得重复请求。
4. 为 `BottomWebViewDialog` 增加隔离的 `SOURCE_SCOPED` 模式，只复用布局和固定高度能力，统一使用 `heightRatio=0.78`。
5. 安全模式固定认证 origin：仅同源请求合并来源 Header/Cookie，跨源资源全部剥离来源认证。
6. 统一日间、夜间和墨水屏的宿主提示；点击后的 HTML/CSS 与列表结构由书源提供。
7. 防止重复打开，Activity 销毁时取消动作。
8. 安全模式遇到 TLS 错误必须取消；不得沿用现有 `handler.proceed()` 行为。

安全测试：

- `file:`、`content:`、`javascript:`、`intent:` 和云元数据地址；
- 同源局域网地址允许，跨源跳转到私网、loopback 或 link-local 地址拒绝；
- 跨源跳转、开放重定向、恶意 iframe；
- 外部头像请求不得携带 Authorization/Cookie；
- 同源到跨源重定向必须先剥离来源认证；
- TLS 证书错误必须失败关闭；
- 超大 HTML、递归打开、重复点击；
- token 不进入 URL、日志、WebView Cookie和导出数据；
- 恶意 `actionData` 不能改变请求域名或注入 Header。

阶段门禁：WebView instrumentation 和反向安全测试通过。

### 阶段 6：LegadoHub 首个适配

该阶段在同级 `../legado-hub` 仓库实施，客户端仍不得增加任何 LegadoHub 分支。

任务：

1. 生成书源声明 `ruleContent.chapterComment`。
2. `url` 从当前章节 URL 派生摘要地址，每章独立。
3. `data` 把后端字段转换为标准载荷：
   - `matchedParagraphIndex -> paragraphIndex`
   - `matchedParagraphCount -> paragraphCount`
   - 来源段落 ID -> `id`
   - 评论数量 -> `counts.total/counts.hot`
   - 页热评候选 -> `pageEligible`
4. `action` 分别把段级 ID、页级 ID集合和章末事件转换为书源自己的展示地址。
5. 首个起点适配默认关闭段级入口、启用页级下拉和章末入口；切换只改书源 `display`，不改客户端。
6. 能力探测成功时不再向正文插入页气泡或章末图片。
7. 能力探测失败时只追加章末入口，不追加页气泡或段级入口。
8. 后端继续使用 `Catalog -> PluginScheduler -> Web/App 插件`。
9. 更新书源 `lastUpdateTime`，并记录普通客户端需要一次性清理旧章节缓存。

自动测试：

- 生成 JSON 可被普通客户端旧模型解析；
- 普通客户端重导出可能丢失增强字段，迁移文档要求从原始订阅 URL 重新导入；
- 无能力方法时只生成章末入口；
- 有 v1 能力时正文纯净；
- 能力判断和正文分支在一次规则执行中完成，不存在先追加后删除的竞态；
- 定制客户端命中旧缓存 sidecar 时会重抓，普通客户端旧缓存行为有明确升级提示；
- 当前章、预加载下一章生成不同摘要请求；
- App 插件优先、Web 插件降级仍由后端 catalog 决定；
- VIP 评论、回复分页和授权契约不退化；
- 测试中不存在插件之外的目标站请求。

阶段门禁：运行 LegadoHub 定向评论测试和前后端相关门禁，不在每个小修改后跑全量。

### 阶段 7：整合、性能和真机验收

性能预算：

- 摘要解析和锚点匹配放在后台线程；
- 当前页切换只做集合投影，不做网络请求；
- 预加载评论摘要并发默认 `1`；
- 单书评论摘要磁盘缓存可配置上限，初始建议 `20 MiB`；
- 下拉过程不得触发网络、JSON 解析或 WebView 创建；
- 中端设备连续翻页无可感知掉帧。

真机矩阵：

- Android 8、11、14/15 至少三个 API 档；
- 覆盖、滑动、仿真、无动画；
- 连续滚动的菜单降级；
- 日间、夜间、墨水屏；
- 字号、行距、边距、横竖屏、双页；
- 长按选文、搜索定位、自动翻页、朗读；
- 当前章和预加载后续章节；
- Wi-Fi 断开、401、登录过期、慢网络；
- 免费章、VIP 预览章和已购买章；
- 普通客户端只显示章末入口。

人工验收通过前，不发布正式 APK。

## 6. 最终验证

### Legado-E

当前仓库要求 JDK 17、Android SDK 36，并已在该环境完成构建。`connectedAppDebugAndroidTest` 仍需要真机或模拟器，本轮未执行；其余本地自动门禁已有真实通过记录。

首先执行 variant discovery，并把它作为后续命令的硬前置：

```powershell
.\gradlew.bat :app:tasks --all --no-daemon
```

只有确认任务实际存在后，才执行并记录真实任务名、退出码和产物：

```powershell
.\gradlew.bat test --no-daemon
.\gradlew.bat lint --no-daemon
.\gradlew.bat assembleAppDebug --no-daemon
.\gradlew.bat assembleAppRelease --no-daemon
.\gradlew.bat connectedAppDebugAndroidTest --no-daemon
```

最后一项必须有 emulator 或真机。任何任务未在 discovery 输出中出现时都不得执行或报告通过。

### LegadoHub

阶段 6 定向测试通过后，最终提交前执行其规范全门禁：

```powershell
.\verify.ps1
```

全门禁约需数分钟，应在功能与真机 smoke 已收敛后运行一次，而不是每写一点代码就运行。

## 7. 发布门禁

必须全部满足：

1. 客户端代码搜索不到书源、站点或后端专用判断。
2. 两个结构不同的虚拟书源通过同一协议测试。
3. 普通客户端只显示章末入口。
4. 预加载章节可在切换后立即获得自己的页热评。
5. 下拉手势不破坏翻页、选文、自动翻页和朗读。
6. 评论请求仍可追踪到插件调度链。
7. 授权信息未进入 URL、HTML、Cookie 或日志。
8. Legado-E 单测、lint、debug/release 构建通过。
9. LegadoHub 定向测试和 `verify.ps1` 通过。
10. 真机矩阵的核心路径完成并保留记录。

## 8. 实施顺序与提交建议

建议每阶段一个可回退提交：

1. `docs(reader): 固定通用三级评论边界`
2. `feat(reader): 新增三级章节评论协议`
3. `feat(reader): 加载并缓存章节评论摘要`
4. `feat(reader): 映射真实分页段落`
5. `feat(reader): 支持下拉打开页热评`
6. `feat(reader): 执行通用评论动作`
7. `feat(source): 适配三级章节评论协议`
8. `test(reader): 完成章节评论回归验证`

不得在协议、手势和 LegadoHub 适配尚未各自通过阶段测试时压成一个不可审查的大提交。

## 9. 已确认的产品项

本轮已固定：

- 定制 APK 展示名为“小说聚合阅读”，使用独立 application ID；
- 定制分支使用 `codex/legadohub-reader`，默认推送到 `XziXmn/legado-X`；原仓库只作为上游同步源；
- 连续滚动模式 v1 使用阅读菜单中的“本页热评”；
- 原生章末入口文案为“本章说”，评论弹窗高度固定为屏幕的 `0.78`；
- 普通客户端继续通过书源章末入口兼容，升级后需按发布提示清理旧章节缓存并从原始订阅 URL 重新导入书源。
