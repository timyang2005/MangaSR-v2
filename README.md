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

*需要 Android 8.0 或更高版本。*

## 特性

<div align="left">

* **实时超分辨率**
  - Anime4K 实时算法（CPU，无需模型文件）
  - Waifu2X（NCNN Vulkan GPU 加速）
  - Real-ESRGAN（NCNN Vulkan GPU 加速）
  - 支持 2x/3x/4x 放大倍率
  - 可调节降噪级别（无/低/中/高）
* 本地内容阅读
* 可配置的阅读器，包含多种查看器、阅读方向和其他设置
* 跟踪器支持：[MyAnimeList](https://myanimelist.net/)、[AniList](https://anilist.co/)、[Kitsu](https://kitsu.app/)、[MangaUpdates](https://mangaupdates.com)、[Shikimori](https://shikimori.one) 和 [Bangumi](https://bgm.tv/)
* 分类组织你的书库
* 亮/暗主题
* 自动更新书库的新章节
* 创建本地备份以便离线阅读，或备份到你想要的云服务
* 以及更多...

</div>

## 超分辨率技术详解

### 核心算法概览

本项目集成了三种主流的超分辨率算法，针对漫画/动画图片进行优化：

#### 1. Anime4K
- **实现位置**: [Anime4KProcessor.kt](file:///workspace/mihon-source/core/superresolution/src/main/java/mihon/core/superresolution/Anime4KProcessor.kt)
- **算法特点**: 实时 CPU 算法，无需模型文件，基于图像梯度和颜色空间的数学运算
- **技术参数**:
  - 迭代次数 (PASSES): 2
  - 颜色强度 (STRENGTH_COLOR): 0.3
  - 梯度强度 (STRENGTH_GRADIENT): 1.0
- **优势**: 极快速度，无需额外模型，低内存占用
- **使用场景**: 快速浏览，低配置设备

#### 2. Waifu2X
- **实现位置**: [Waifu2xProcessor.kt](file:///workspace/mihon-source/core/superresolution/src/main/java/mihon/core/superresolution/Waifu2xProcessor.kt)
- **技术栈**: NCNN 神经网络 + Vulkan GPU 加速
- **模型**: CNN 架构，专注于降噪和放大
- **瓦片大小**: 200x200 像素（避免大内存占用）
- **优势**: 质量高，GPU 加速性能优秀
- **使用场景**: 高质量阅读，中高端设备

#### 3. Real-ESRGAN (Anime)
- **实现位置**: 预留接口，与 Waifu2X 共用 NCNN 框架
- **技术栈**: NCNN + Vulkan 加速
- **模型**: Real-ESRGAN 专为动漫优化的模型
- **优势**: 最先进的超分效果，支持 2x/4x 缩放
- **使用场景**: 最佳画质，高性能设备

### 技术架构

```
┌─────────────────────────────────────────────────────────────┐
│                      Mihon 主程序                            │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌──────────────────────┐     ┌───────────────────────┐    │
│  │  阅读器设置界面      │────▶│   超分设置管理        │    │
│  │  (全局/单本设置)    │     │   (数据库 + 偏好)    │    │
│  └──────────────────────┘     └───────────────────────┘    │
│                                                              │
│  ┌───────────────────────────────────────────────────────┐ │
│  │                图片加载流程                            │ │
│  │  ┌───────────────────────────────────────────────────┐ │ │
│  │  │  Coil 3 Image Loader                              │ │ │
│  │  ├───────────────────────────────────────────────────┤ │ │
│  │  │  [SuperResolutionInterceptor]                      │ │ │
│  │  │  (自动应用超分)                                   │ │ │
│  │  └───────────────────────────────────────────────────┘ │ │
│  └───────────────────────────────────────────────────────┘ │
│                              │                               │
├──────────────────────────────┼───────────────────────────────┤
│                              ▼                               │
│  ┌───────────────────────────────────────────────────────┐ │
│  │           SuperResolutionManager                      │ │
│  │  (核心调度 + 缓存管理 + 线程安全)                      │ │
│  └───────────────────────────────────────────────────────┘ │
│                              │                               │
│        ┌─────────────────────┼─────────────────────┐         │
│        ▼                     ▼                     ▼         │
│  ┌──────────────┐     ┌──────────────┐   ┌───────────────┐ │
│  │Anime4K (CPU) │     │ Waifu2X (GPU)│   │Real-ESRGAN    │ │
│  │  [Processor] │     │  [Processor] │   │  [Processor]  │ │
│  └──────────────┘     └──────────────┘   └───────────────┘ │
│                              │                               │
├──────────────────────────────┼───────────────────────────────┤
│                              ▼                               │
│  ┌───────────────────────────────────────────────────────┐ │
│  │        JNI (Java Native Interface)                    │ │
│  │  ┌─────────────────┐ ┌─────────────────────────────┐  │ │
│  │  │libanime4kcpp.so│ │libwaifu2x-ncnn-vulkan.so    │  │ │
│  │  │(C++ native code)│ │(NCNN + Vulkan engine)       │  │ │
│  │  └─────────────────┘ └─────────────────────────────┘  │ │
│  └───────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

### 关键技术实现

#### 1. Coil 3 拦截器集成
- **实现位置**: [SuperResolutionInterceptor.kt](file:///workspace/mihon-source/app/src/main/java/eu/kanade/tachiyomi/data/coil/SuperResolutionInterceptor.kt)
- **工作流程**:
  ```kotlin
  ImageRequest → Chain.proceed() → SuccessResult → BitmapImage
                                                      ↓
                                              超分辨率处理
                                                      ↓
                                  Result.copy(image = srBitmap.asImage())
  ```
- **限制条件**: 仅处理 ARGB_8888 格式的图片，最大输入尺寸 2048x2048 像素

#### 2. LRU 缓存机制
- **实现位置**: [SuperResolutionManager.kt](file:///workspace/mihon-source/core/superresolution/src/main/java/mihon/core/superresolution/SuperResolutionManager.kt#L19-L21)
- **配置**: 最大缓存大小 = 应用可用内存的 1/8
- **缓存键**: `{模型}_{宽}x{高}_{缩放倍率}`
- **性能优化**: 避免重复处理相同图像

#### 3. 线程安全与异步处理
- **互斥锁**: 使用 `Mutex` 确保模型切换和处理过程线程安全
- **协程调度**: IO 密集型操作使用 `Dispatchers.IO`，计算密集型使用 `Dispatchers.Default`
- **生命周期管理**: 处理器自动释放资源

#### 4. 模型管理
- **模型存储**: 首次启动时从 APK assets 解压到 `files/models/` 目录
- **动态切换**: 运行时无需重启即可切换模型和缩放倍率
- **GPU 检测**: 自动检测 Vulkan 支持，降级回 CPU 方案

### 已实现功能

| 功能 | 状态 | 技术实现 |
|------|------|----------|
| Anime4K CPU 算法 | ✅ 完整实现 | 纯 C++ 数学运算，无模型文件 |
| Waifu2X NCNN Vulkan | ✅ 完整实现 | NCNN 框架 + Vulkan 加速，瓦片处理 |
| Real-ESRGAN NCNN Vulkan | 🔄 预留 | NCNN 框架 + 优化模型 |
| 全局超分设置 | ✅ | Preferences 持久化存储 |
| 单本超分设置 | ✅ | 数据库字段 + MangaSrPreferences 代理 |
| 2x/3x/4x 缩放 | ✅ | 模型支持的缩放倍率列表 |
| 降噪级别调节 | ✅ | 0-3 级噪音移除参数 |
| GPU 加速检测 | ✅ | VulkanHelper.isVulkanSupported() |
| 模型文件下载 | ✅ | 内置到 APK assets，首次启动解压 |
| LRU 缓存优化 | ✅ | Android.util.LruCache |
| 数据库迁移 | ✅ | SQLDelight schema update |

## 性能优化策略

1. **条件应用**: 仅在用户开启超分时才应用处理
2. **尺寸限制**: 跳过大于 2048x2048 的图像
3. **内存管理**: 最大输入尺寸限制 + LRU 缓存
4. **GPU 优先**: 检测 Vulkan 支持并优先使用
5. **瓦片处理**: 大图像分片处理，避免 OOM
6. **线程安全**: Mutex 保证并发安全

## 编译

```bash
# 克隆项目
git clone https://github.com/timyang2005/mihon-super-resolution.git
cd mihon-super-resolution

# 构建调试版本
./gradlew assembleDebug
```

## 参与贡献

[行为准则](./CODE_OF_CONDUCT.md) · [贡献指南](./CONTRIBUTING.md)

欢迎提交 Pull Request。对于重大更改，请先打开 Issue 讨论你想要更改的内容。

在报告新 Issue 之前，请查看 [FAQ](https://mihon.app/docs/faq/general)、[更新日志](https://mihon.app/changelogs/) 和已打开的 [Issues](https://github.com/mihonapp/mihon/issues)；如果你有任何问题，请加入我们的 [Discord 服务器](https://discord.gg/mihon)。

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
