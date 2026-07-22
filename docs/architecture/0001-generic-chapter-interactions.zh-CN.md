# ADR-0001：通用三级章节评论能力

- 状态：已实现，待真机验收
- 日期：2026-07-22
- 基准提交：`21855a7bf901becfd1caba5cf30a3c84fd1533e1`
- 适用分支：`codex/legadohub-reader`

## 实现结论

截至 2026-07-22，协议、摘要缓存、相邻章节预加载、模糊锚点、真实分页投影、三级入口、分页下拉手势、滚动模式菜单入口、来源动作和隔离 WebView 均已实现。LegadoHub 已作为首个书源适配，评论只通过定制客户端的原生 v2 契约显示，正文不再注入任何兼容入口。

自动验证已完成：Legado-E 的 Debug/Release 单元测试、lint、Debug/Release 构建通过；LegadoHub 定向测试与 `verify.ps1` 完整门禁通过。真机上的翻页模式、主题、登录态、预加载和评论交互仍属于发布前人工验收，不以 JVM 测试替代。

## 背景

旧方案只能把章节评论入口伪装成正文图片或 HTML 片段。该方式发生在真实分页之前，因此存在三个结构性问题：

1. 入口位置只能按字符数估算，无法稳定落在当前页。
2. 图片点击、预加载和正文磁盘缓存耦合，书源更新后旧入口可能继续存在。
3. 为某个站点或某个后端写客户端特判，会把协议、分页和 UI 绑死在单一书源上。

起点客户端的已确认交互是：竖向拖动达到 `52dp` 后触发一次触觉反馈，松手时回弹并打开当前页热评；购买页、无当前页或未达到阈值时不触发。这个行为只作为交互参考，不作为协议来源。

## 决策

在 Legado-E 中实现来源无关的“章节评论”能力，并把客户端职责固定为三个层级：

1. `segment`：把来源评论锚点绑定到真实正文段落。
2. `page`：根据真实分页聚合当前页可见段落，并提供页热评入口。
3. `chapter`：在章末提供全章评论入口。

客户端只理解这三个通用层级，不理解起点、LegadoHub、插件 ID 或固定 API 路径。

起点评论是首个适配和验收样例，但不拥有客户端特权。任何书源只要声明同一契约，都可以获得相同的段级投影、页级聚合、下拉手势、章末入口、缓存和安全处理。

### 硬边界

客户端实现中禁止出现以下判断：

- `bookSourceUrl`、书源名称或插件 ID 白名单；
- `qidian`、`LegadoHub`、`/reviews` 等固定字符串；
- 按特定服务端字段直接渲染 UI；
- 绕过书源规则直接调用目标站点或后端。

协议适配只允许存在于书源规则中。客户端接收规范化后的通用数据。

## 承载位置

在 `ContentRule` 中新增可选字段 `chapterComment`，不在 `BookSource` 根实体增加数据库列。

理由：

- `ruleContent` 本来就是 JSON 列，增加嵌套字段不需要 Room migration；
- 旧客户端会忽略未知 JSON 字段；
- 新客户端可以在正文缓存命中时独立加载评论摘要；
- 避免复活已停用且语义不完整的 `ruleReview`。

建议模型：

```kotlin
@Parcelize
data class ChapterCommentRule(
    var protocolVersion: Int = 2,
    var url: String? = null,
    var data: String? = null,
    var action: String? = null,
    var display: ChapterCommentDisplayRule? = null,
    var cacheTtlSeconds: Int = 300,
) : Parcelable
```

字段语义：

- `url`：按现有 `AnalyzeUrl` 语义生成摘要请求，自动继承书源 Header、Cookie 与登录状态。
- `data`：按现有规则或 JS 语义把响应转换为标准载荷；为空时响应本身必须符合标准载荷。
- `action`：收到段级、页级或章末事件后，返回标准动作对象。规则可以使用事件中的锚点 ID，但客户端不解释 ID。
- `display`：声明三个层级是否启用，并从客户端支持的入口预设中选择样式；不得注入任意原生 View 或 Canvas 代码。
- `cacheTtlSeconds`：只控制摘要缓存；完整评论列表仍在用户打开后加载。

字段名和最终 Kotlin 类型在阶段 1 以测试固定，之后不得无版本修改。

## 客户端能力探测

新增通用 JS 能力探测：

```javascript
java.hasReaderCapability("chapter-comments", 2)
```

约束：

- 未知能力返回 `false`，不抛异常。
- 普通客户端不存在该方法；评论能力不做降级，也不向正文注入替代入口。
- 当前实现只对精确版本 `2` 返回 `true`，v1 载荷不会被解析或展示。
- 能力名和版本是客户端协议，不是服务端或书源身份。
- 不通过访问某个 HTTP 地址猜测客户端能力。
- 方法添加在公共 `io.legado.app.help.JsExtensions` 接口；`AnalyzeRule : JsExtensions` 使所有书源规则 JS 同步获得该方法。不能只把方法放进阅读 Activity 或登录 WebView。

## 三级接口与显示所有权

### 段级接口

- 来源提供段落锚点、评论数量和不透明 ID。
- 客户端完成模糊匹配、真实分页投影、入口绘制和命中测试。
- 段级入口是可选能力；书源可以声明 `enabled=false`。
- v2 入口预设只允许 `dot`、`count`、`labelCount` 和 `none`。
- 入口由 `ContentTextView` 持有的渲染 overlay 绘制在段落末行或安全边栏，不插入正文字符，不参与复制和 TTS。
- overlay 使用 `TextLine.lineTop/lineBottom/lineEnd` 和 `ContentTextView.relativeOffset` 计算坐标，因此连续滚动、双页和相邻页绘制共用同一坐标系。
- `ContentTextView.click()` 先做 overlay 命中测试，再走现有正文图片点击；长按选文路径不进入 overlay 点击。

### 页级接口

- 页不是来源数据实体，而是客户端真实布局结果。
- 客户端收集当前 `TextPage` 可见段落的锚点 ID，并聚合数量。
- 来源决定是否启用页级入口、标签和点击后的页面；客户端决定手势仲裁和入口位置。
- v2 分页模式使用向下拖动；连续滚动模式使用阅读菜单降级。
- 来源可以只启用页级接口而隐藏全部段级入口。

### 章末接口

- 来源可以提供不可点击的章末补充、全章评论数量、标签、预览和动作数据。
- 客户端用原生章末 block 依次显示补充卡片与评论入口，不把图片或 HTML 写进正文。
- `TextChapterLayout` 把 block 作为非文本页面元素测量：末页空间足够时追加到末页，不足时创建一个可由 `TextPageFactory` 正常访问的新页。
- `TextPage` 增加独立的 `blocks` 集合；block-only page 的章节位置取章节文本末尾，不能调用空 `textLines.first()`。
- 章末 block 可以参与最后一页布局和 `pageSize`，但不进入正文字符串、复制、搜索、导出或 TTS。
- 摘要晚到需要重排时，先保存当前字符位置，重新生成 `TextChapter.pages`，再恢复到包含该字符位置的页面。

### 样式边界

来源可以提供：

- 三个层级的启用状态；
- 客户端支持的入口预设、短标签和数量显示方式；
- 点击后 `sourceWebView` 的 HTML、CSS、图标、列表结构和交互；
- 弹窗的受限展示参数，例如固定高度比例。

客户端保留：

- 入口的布局边界、最小点击区域、无障碍描述和主题对比度；
- 下拉阈值、翻页冲突处理和动画状态机；
- WebView 同源认证、安全策略和资源上限。

来源不得通过任意坐标、负边距、脚本绘制或原生类名控制阅读页布局。否则通用协议会退化成站点专用 UI 注入。

显示规则示例：

```json
{
  "segment": {"enabled": false, "preset": "count"},
  "page": {"enabled": true, "preset": "pull", "countField": "total"},
  "chapter": {"enabled": true, "preset": "summaryRow"}
}
```

预设和字段是版本化枚举。客户端遇到未知预设时使用该层级默认值，不执行来源提供的任意布局代码。

## 标准载荷 v2

```json
{
  "version": 2,
  "segments": [
    {
      "id": "opaque-id",
      "paragraphIndex": 12,
      "paragraphCount": 1,
      "excerpt": "可选的正文片段",
      "counts": {
        "total": 36,
        "hot": 3
      },
      "pageEligible": true,
      "actionData": {}
    }
  ],
  "author": {
    "label": "作者名",
    "badge": "作家说",
    "counts": {"total": 0, "hot": 0},
    "actionData": null,
    "previews": ["作者留在章末的补充"]
  },
  "chapter": {
    "label": "本章说",
    "counts": {
      "total": 227,
      "hot": 12
    },
    "actionData": {},
    "previews": [
      "书友甲：第一条章末热评",
      "书友乙：第二条章末热评"
    ]
  }
}
```

### 字段规则

- `version` 必须等于客户端支持的协议版本。
- `id` 是来源侧不透明标识；客户端只去重和回传。
- `paragraphIndex` 是经过正文清洗后的零基段落提示，不是绝对可信位置。
- `paragraphCount` 默认 `1`，表示锚点覆盖的连续段落数。
- `excerpt` 是可选模糊匹配依据；存在时优先用于校验位置。
- `counts.total` 和 `counts.hot` 是非负整数；页入口默认对可见段落的 `total` 去重求和，来源可在显示规则中选择展示 `hot`。
- `pageEligible` 由来源决定该段是否进入页热评集合；客户端不自行定义“热门”。
- `actionData` 只在动作规则执行时回传，不写日志、不拼接到正文。
- `author` 是可选的通用章末补充卡片；`actionData=null` 表示只展示、不响应点击，`badge` 是可选短角标。
- `author.previews` 与 `chapter.previews` 可选，各最多包含 `3` 条摘要；客户端只消费数组，不兼容旧单值摘要字段。
- `chapter` 可缺失；缺失时不显示原生章末入口。

### 载荷限制

- 单章摘要最大 `256 KiB`。
- 单章最多 `200` 个段落锚点。
- 单个 `id` 和 `badge` 最大 `256` 字符，单个 `excerpt` 或预览最大 `512` 字符。
- 单个章末卡片最多显示 `3` 条 `previews`；超限或元素类型错误时整份载荷失败关闭。
- `paragraphIndex`、`paragraphCount` 和各类 count 做范围校验与饱和转换。
- 超限、结构错误或版本未知时整份载荷失败关闭，不影响正文阅读。

## 段落锚点解析

不得向正文插入零宽字符、图片占位或不可见标记。它们会改变字符位置、分页和缓存内容。

客户端在正文完成清洗后建立段落索引，并按以下顺序解析每个锚点：

1. `paragraphIndex` 指向的段落与 `excerpt` 规范化后精确匹配。
2. 在提示位置前后固定窗口内做规范化模糊匹配。
3. 全章只存在唯一高置信候选时接受全局匹配。
4. 只有索引且索引在范围内时，以低置信度接受。
5. 无候选、多个近似候选或越界时丢弃该锚点。

规范化只处理空白、常见标点和 Unicode 形式，不执行用户替换规则之外的语义改写。

协议中的 `paragraphIndex` 固定为零基；Legado-E 正文行的 `TextLine.paragraphNum` 固定为一基，标题和非正文行为 `0`。唯一允许的转换是：

```text
paragraphNum = paragraphIndex + 1
```

段落索引必须从传给 `TextChapterLayout` 的同一份已处理正文建立，不能从原始 HTTP body 或后端正文重新计数。实现时用一条测试同时断言第一个正文段落的 `paragraphIndex=0` 与 `paragraphNum=1`，防止 off-by-one 回归。

当前页的段落集合直接由 `TextPage.lines.asSequence()` 中所有 `paragraphNum > 0` 的行计算，不调用会假定非空正文行的 `TextPage.paragraphs`；`paragraphCount` 展开成连续的 `paragraphNum` 范围。页热评是与该范围相交的已解析锚点去重集合。空页、消息页、购买页和 block-only page 返回空集合，不得抛异常。

同一段落跨越多页时，该锚点在每一个实际显示该段落的页面都可用。双页模式按左右 `TextPage` 分别投影，手势起点所在半页决定事件上下文；无法可靠确定半页时关闭手势，不能把两页评论静默合并。

字体、行距、页边距、横竖屏或双页变化后，只需重新投影到 `TextPage`，不重新请求摘要。

## 通用动作

`action` 规则接收统一事件：

```json
{
  "scope": "page",
  "chapterIndex": 68,
  "pageIndex": 3,
  "segmentIds": ["opaque-id"],
  "count": 36,
  "actionData": []
}
```

`scope` 只允许 `segment`、`page`、`chapter`。段级事件包含一个 `segmentId`，页级事件包含当前页去重后的 `segmentIds`，章末事件不携带段落 ID。

规则返回：

```json
{
  "type": "sourceWebView",
  "url": "https://example.test/view",
  "title": "热评",
  "presentation": "bottomSheet",
  "heightRatio": 0.78
}
```

v2 只实现 `sourceWebView`：

- 初始请求通过 `AnalyzeUrl` 使用当前书源上下文获取，并产出最终 URL、HTML 与经过验证的来源 Header；
- 复用 `BottomWebViewDialog` 的布局和固定高度能力，但必须新增独立的 `SOURCE_SCOPED` 安全模式，不能复用当前不受限的请求转发逻辑；
- Header 与 Cookie 只发送到动作 URL 的同源请求；
- 外部域图片和媒体可以加载，但不得附加书源认证 Header；
- 跨源导航、文件协议、`intent:` 和本地地址必须拒绝或二次确认；
- 评论模式遇到 TLS 证书错误必须取消请求，禁止调用 `proceed()`；
- token 不进入 URL、HTML、WebView Cookie、日志或导出的书源 JSON。

`SOURCE_SCOPED` 模式使用固定的认证 origin。首屏只请求一次；后续同源请求由客户端合并来源认证 Header，跨源子资源只保留 WebView 自身的无敏感请求头。重定向一旦离开认证 origin，必须先剥离认证信息，再按跨源策略处理。

私有网络地址不是天然非法：LegadoHub 等来源本来可以部署在局域网。只有当动作目标与摘要规则解析出的认证 origin 完全一致时，才允许访问 RFC1918 或 IPv6 ULA 地址，并固定首次解析结果以避免 DNS 重绑定。loopback、link-local、multicast 和云元数据地址无条件拒绝；任何跨源私网请求同样拒绝。

## 阅读交互

### 分页模式

在 `ReadView` 统一仲裁触摸，不修改每一种 `PageDelegate`：

1. 仅当前页存在可用锚点时启用。
2. `ReadView` 新增明确的 `TouchOwner`（`PAGE`、`COMMENT`、`NONE`）和统一的 delegate 转发函数。
3. `ACTION_DOWN` 先交给下拉控制器记录候选，同时保持现有 delegate 的 `DOWN/onDown` 行为，并把 owner 设为 `PAGE`。
4. 第一次超过 touch slop 的 `MOVE` 先由控制器分类；只有手指向下且竖向位移明显大于横向位移时取得所有权。
5. 取得所有权的同一帧通过 `MotionEvent.obtain` 向当前 `PageDelegate` 发送一次合成 `ACTION_CANCEL`，将 owner 改为 `COMMENT`；该帧及后续事件不再转发给 delegate，并取消长按。
6. `ACTION_UP/CANCEL` 只按 owner 路由，不能再以 `pageDelegate.isMoved` 推断所有权。
7. 页面按阻尼移动，最大不超过视图高度的四分之一。
8. 达到 `52dp` 时只触发一次轻触觉反馈并进入 armed 状态。
9. 松手后先回弹；armed 时打开当前页热评，未 armed 时只回弹。
10. 多点触控或系统 `ACTION_CANCEL` 会复位位移和控制器；一旦 delegate 已被取消，本次触摸序列不会尝试恢复 delegate。

### 滚动模式

滚动模式的竖向手势是核心导航，v2 禁止直接抢占。该模式提供阅读菜单中的“本页热评”动作作为可达性降级；只有在后续能证明不破坏连续滚动时，才允许增加边界下拉手势。

### 禁止触发状态

- 当前页无可用锚点；
- 正文加载中、错误页、购买页或消息页；
- 正在选文、长按、搜索定位或多点触控；
- 翻页动画、自动翻页、朗读交互或长截图进行中；
- 横屏双页的页归属尚未确定；
- 弹窗已打开或动作仍在执行。

## 客户端支持边界

- 定制客户端：正文保持纯净，由原生 v2 能力按书源配置显示段级、页级和章末入口。
- 普通客户端：忽略未知的 `chapterComment` 字段，正文照常读取，但不显示任何评论入口。
- 生成书源不执行能力探测分支，不生成 SVG、HTML、图片或 `showBrowser` 评论入口。
- 普通客户端再次导出书源时可能丢失未知字段；切换到定制客户端后必须从原始订阅 URL 重新导入，不能把旧客户端导出的 JSON 当作完整增强书源。

## 缓存与预加载

- 评论摘要与正文缓存分离。
- 缓存键包含书源 URL、`ContentRule.chapterComment` 指纹、书籍 URL、章节 URL和协议版本。
- 正文缓存 sidecar 保存 `ruleContent` 指纹，用于在规则更新后重抓当前章和预加载窗口；它不改变正文缓存文件格式。
- 当前章为高优先级；上一章和下一章只预加载摘要，不预加载完整评论。
- 同一章节的并发请求合并，预加载并发数默认 `1`。
- 网络失败时可使用未超过最大陈旧期限的旧摘要；没有旧摘要时静默关闭入口。
- 评论加载失败不得把正文标记为失败，也不得阻塞翻页。
- 书源规则更新只失效对应书源的评论缓存，不清空全局正文缓存。

## 被拒绝的方案

1. **在客户端硬编码 LegadoHub 或起点接口**：破坏通用性和插件边界。
2. **继续插入每段图片或 SVG**：分页前估算不可靠，点击和缓存问题无法根治。
3. **用不可见字符携带段落 ID**：污染正文和字符位置，替换规则也可能删除标记。
4. **复活 `ruleReview`**：现有类型聚焦发布、点赞和回复，转换器固定返回 `null`，语义和实现均不完整。
5. **预加载全部评论和回复**：放大网络、内存和目标站压力；阅读预加载只需要摘要。
6. **滚动模式无条件劫持下滑**：会破坏最基本的阅读导航。
7. **把认证 token 放入评论 URL**：会进入历史、日志、截图和第三方资源请求。
8. **按站点文本做客户端模糊识别**：协议漂移后会静默关联到错误段落。
9. **直接复用现有 WebView 请求转发逻辑**：当前逻辑会把 Header/Cookie 合并到跨源资源并忽略 TLS 错误，不满足互动数据的认证边界。

## 影响

正面影响：

- 分页位置由客户端真实布局决定；
- 新书源可以复用同一能力；
- 普通客户端不会因未知评论字段影响正文阅读；
- 正文、评论摘要和完整评论按责任分层；
- 登录、Cookie 和 Header 继续由书源体系管理。

成本与风险：

- 需要新增协议模型、加载器、缓存、锚点解析器、手势控制器和动作执行器；
- `ReadView` 是高风险交互入口，必须先把手势判定抽成纯状态机测试；
- 当前仓库的 Android CI 配置有陈旧变体，不能把现有 workflow 全绿当作唯一发布证明；
- 真机上的多翻页模式、预加载和 WebView 授权仍需要人工验收。
