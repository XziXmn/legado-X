# legado-X

基于 [Legado-E](https://github.com/Luoyacheng/legado-E) / [Legado](https://github.com/gedoor/legado) 的个人修改版。配套后端：[LegadoHub](https://github.com/XziXmn/legado-hub)。

[![Releases](https://img.shields.io/github/v/release/XziXmn/legado-X?include_prereleases)](https://github.com/XziXmn/legado-X/releases)
[![License](https://img.shields.io/badge/License-GPL--3.0-blue)](LICENSE)
[![LegadoHub](https://img.shields.io/badge/Backend-LegadoHub-2496ED?logo=github)](https://github.com/XziXmn/legado-hub)

## 下载

[GitHub Releases](https://github.com/XziXmn/legado-X/releases)

- 正式版：应用名「阅读」，包名 `io.legado.app.release`（可覆盖常见安装）  
- 测试版：应用名「阅读·测试」，包名 `io.legado.app.beta`（独立安装，不覆盖正式版）  
- 签名：公开测试密钥  
- 软件不提供内容，书源等需自行导入  

## 独占功能

- 阅读页支持章节评论：段落旁入口、本页热评下拉、章末评论入口
- 书源可声明评论能力；不支持的客户端会自动降级，不影响正常阅读
- 打开评论时尽量沿用书源登录状态，并限制不安全的跨站访问

章节评论走通用书源契约，不绑定单一站点。配套后端 [LegadoHub](https://github.com/XziXmn/legado-hub) 已作为首个适配：管理员统一维护书源，读者导入专属书源链接即可搜索、订阅、阅读，并在本客户端查看段评 / 页热评 / 章末评论。

[更新日志](app/src/main/assets/updateLog.md)

## 免责声明

阅读通过用户自定义的第三方书源获取内容，不对书源内容及其合法性负责。请自行判断风险。权利人如需处理侵权内容，请联系维护并提供权属证明。

## 相关项目

- [LegadoHub](https://github.com/XziXmn/legado-hub) — 自托管小说聚合订阅服务，为阅读提供稳定的后端书源。导入其发放的专属书源后可搜索、订阅、阅读，并启用本客户端的章节评论。

## 友情链接

- [LINUX DO](https://linux.do/) — LINUX DO 社区

## 许可

[GPL-3.0](LICENSE) · 致谢 [gedoor/legado](https://github.com/gedoor/legado)、[Luoyacheng/legado-E](https://github.com/Luoyacheng/legado-E) 与开源依赖作者。
