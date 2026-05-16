<div align="center">

<a href="https://mihon.app">
    <img src="./.github/assets/logo.png" alt="Mihon logo" title="Mihon logo" width="80"/>
</a>

# Mihon 超分版

### 全功能漫画阅读器 + 实时超分辨率
在你的 Android 设备上更轻松地发现和阅读漫画、条漫、漫画等内容，并享受实时超分辨率带来的清晰画面。

[![Discord server](https://img.shields.io/discord/1195734228319617024.svg?label=&labelColor=6A7EC2&color=7389D8&logo=discord&logoColor=FFFFFF)](https://discord.gg/mihon)
[![License: Apache-2.0](https://img.shields.io/github/license/mihonapp/mihon?labelColor=27303D&color=0877d2)](/LICENSE)

## 下载

*Requires Android 8.0 or higher.*

## 特性

<div align="left">

* **实时超分辨率**
  - Anime4K 实时算法（CPU，无需模型文件）
  - Waifu2X（NCNN Vulkan 加速）
  - Real-ESRGAN（NCNN Vulkan 加速）
  - 支持 2x/3x/4x 放大
  - 可调节降噪级别
* 本地内容阅读
* 可配置的阅读器，包含多种查看器、阅读方向和其他设置
* 跟踪器支持：[MyAnimeList](https://myanimelist.net/)、[AniList](https://anilist.co/)、[Kitsu](https://kitsu.app/)、[MangaUpdates](https://mangaupdates.com)、[Shikimori](https://shikimori.one) 和 [Bangumi](https://bgm.tv/)
* 分类组织你的书库
* 亮/暗主题
* 自动更新书库的新章节
* 创建本地备份以便离线阅读，或备份到你想要的云服务
* 以及更多...

</div>

## 超分辨率架构

```
Mihon 主程序
    │
    ├─ 阅读器设置
    │   ├─ 全局超分设置
    │   └─ 单本设置（Per-manga）
    │
    └─ 图片加载
        └─ Coil 3 拦截器
            └─ SuperResolutionManager
                ├─ Anime4K（CPU 实时算法）
                ├─ Waifu2X（NCNN Vulkan 加速）
                └─ Real-ESRGAN（预留）
```

## 编译

```bash
./gradlew assembleDebug
```

## 参与贡献

[行为准则](./CODE_OF_CONDUCT.md) · [贡献指南](./CONTRIBUTING.md)

欢迎提交 Pull Request。对于重大更改，请先打开 Issue 讨论你想要更改的内容。

在报告新 Issue 之前，请查看 [FAQ](https://mihon.app/docs/faq/general)、[更新日志](https://mihon.app/changelogs/) 和已打开的 [Issues](https://github.com/mihonapp/mihon/issues)；如果你有任何问题，请加入我们的 [Discord 服务器](https://discord.gg/mihon)。

### 仓库

[![mihonapp/website - GitHub](https://github-readme-stats.vercel.app/api/pin/?username=mihonapp&repo=website&bg_color=161B22&text_color=c9d1d9&title_color=0877d2&icon_color=0877d2&border_radius=8&hide_border=true&description_lines_count=2)](https://github.com/mihonapp/website/)
[![mihonapp/bitmap.kt - GitHub](https://github-readme-stats.vercel.app/api/pin/?username=mihonapp&repo=bitmap.kt&bg_color=161B22&text_color=c9d1d9&title_color=0877d2&icon_color=0877d2&border_radius=8&hide_border=true&description_lines_count=2)](https://github.com/mihonapp/bitmap.kt/)

### 致谢

感谢所有做出贡献的人！

<a href="https://github.com/mihonapp/mihon/graphs/contributors">
    <img src="https://contrib.rocks/image?repo=mihonapp/mihon" alt="Mihon app contributors" title="Mihon app contributors" width="800"/>
</a>

### 免责声明

此应用程序的开发者与任何可用的内容提供商没有任何关联，且此应用程序不托管任何内容。

### 许可证

<pre>
Copyright © 2015 Javier Tomás
Copyright © 2024 Mihon Open Source Project
Copyright © 2026 Super Resolution Edition

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
</pre>

</div>
