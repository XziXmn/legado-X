# legado-X

基于 [Legado-E](https://github.com/Luoyacheng/legado-E) / [Legado](https://github.com/gedoor/legado) 的开源阅读客户端，在通用书源阅读能力之上增加**章节评论**（段级 / 页热评 / 章末）。

[![Releases](https://img.shields.io/github/v/release/XziXmn/legado-X?include_prereleases)](https://github.com/XziXmn/legado-X/releases)
[![License](https://img.shields.io/badge/License-GPL--3.0-blue)](LICENSE)

## 下载

[GitHub Releases](https://github.com/XziXmn/legado-X/releases)

- 应用名：阅读  
- 包名：`io.legadox.app`（可与原版 / 上游并存）  
- 签名：公开测试密钥（与上游开源分发方式一致）  
- 软件不提供内容，书源等需自行导入  

## 本仓库改动

- 通用章节评论协议（`ContentRule.chapterComment`）
- `java.hasReaderCapability("chapter-comments", 1)` 能力探测
- 评论 WebView 来源作用域安全策略  

说明：[架构 ADR](docs/architecture/0001-generic-chapter-interactions.zh-CN.md) · [更新日志](app/src/main/assets/updateLog.md)

## 构建

```bash
./gradlew :app:assembleAppDebug
./gradlew :app:assembleAppRelease   # 需配置公开测试密钥，见 .github/workflows
```

## 上游

| 远端 | 用途 |
|---|---|
| `origin` → [XziXmn/legado-X](https://github.com/XziXmn/legado-X) | 推送与发版 |
| `upstream` → [Luoyacheng/legado-E](https://github.com/Luoyacheng/legado-E) | 仅同步，默认不推送 |

详见 [AGENTS.md](AGENTS.md)。API 见 [api.md](api.md)。

## 免责声明

阅读通过用户自定义的第三方书源获取内容，不对书源内容及其合法性负责。请自行判断风险。权利人如需处理侵权内容，请联系维护并提供权属证明。

## 许可

[GPL-3.0](LICENSE) · 致谢 [gedoor/legado](https://github.com/gedoor/legado)、[Luoyacheng/legado-E](https://github.com/Luoyacheng/legado-E) 与开源依赖作者。
