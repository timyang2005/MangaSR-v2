<p align="center">
  <h1 align="center">MangaSR v2</h1>
  <p align="center">
    AI 驱动的实时超分辨率漫画阅读器<br/>
    基于 <a href="https://github.com/mihonapp/mihon">Mihon</a>，搭载 Real-ESRGAN & Real-CUGAN + NCNN Vulkan
  </p>
</p>

---

## ✨ 特性

- 🎨 **5 款内置 AI 超分模型** — Real-CUGAN (2x/4x) + Real-ESRGAN (Anime Fast/Plus, General Fast)
- ⚡ **Vulkan GPU 加速** — 基于 NCNN 推理框架，利用手机 GPU 实现高速推理
- 📖 **智能预加载** — 自动预加载当前页前后 5 张图片的超分结果，阅读零等待
- 🔄 **渐进式替换** — 先显示原图，超分完成后自动替换，不阻塞阅读
- 🖤 **黑白漫画优化** — 灰度级量化 + Otsu 二值化 + 点阵密度校正
- 🎯 **SR 状态指示器** — 实时显示超分处理进度（进行中/已完成）
- 💾 **多级缓存** — 内存缓存 + 磁盘缓存，避免重复处理
- 🔧 **灵活配置** — 全局设置 + 单漫画设置，降噪/画质/灰度可调

## 🤖 模型一览

| 模型 | 倍率 | 大小 | 特点 |
|------|------|------|------|
| Real-CUGAN 2x-conservative | x2 | ~2MB | ⚡ 速度最快，2x 对漫画已足够 |
| Real-CUGAN 4x-conservative | x4 | ~2.2MB | 快速 4x 放大 |
| Real-ESRGAN Anime Fast | x4 | ~1.5MB | 动漫专用，体积最小 |
| Real-ESRGAN Anime+ | x4 | ~5MB | 动漫增强，细节恢复最佳 |
| Real-ESRGAN General Fast | x4 | ~3MB | 通用模型，强降噪场景 |

**默认模型**：Real-CUGAN 2x-conservative（速度最快）

## 🏗️ 架构

```
Mihon 主程序
    │
    ├─ 阅读器设置 (ReaderPreferences)
    │   ├─ 全局超分设置 (srEnabled, srModel, srScale, srDenoiseLevel, srQuality)
    │   └─ 单本设置 (MangaSrPreferences)
    │
    ├─ SuperResolutionSync (偏好→引擎同步)
    │
    └─ 图片加载 (Coil 3)
        ├─ SuperResolutionInterceptor (预加载+渐进式替换)
        └─ SRPreloadDispatcher (预加载调度)
            └─ SuperResolutionManager (核心引擎管理)
                ├─ RealESRGANProcessor (NCNN Vulkan 推理)
                │   └─ C++ JNI (realesrgan_jni.cpp)
                │       ├─ NCNN 推理 (Vulkan GPU)
                │       └─ MangaBWPostProcessor (灰度后处理)
                ├─ ModelManager (模型资产管理)
                ├─ VulkanHelper (GPU 检测)
                └─ SRDiskCache (磁盘缓存)
```

## 🔑 核心设计

### 防重影像素处理

在 Tile 切片推理中，框架高层 API 的隐式 RGBA↔RGB 转换可能导致通道错位产生重影。MangaSR v2 采用手动显式像素通道处理：

```
Bitmap (RGBA) ──手动提取 R,G,B──→ float[] (RGB) ──构造──→ ncnn::Mat (3ch)
                                                              ↓
                                                         NCNN 推理
                                                              ↓
ncnn::Mat (3ch) ──手动写回 R,G,B + A=255──→ Bitmap (RGBA)
```

**关键规则**：
- Bitmap → NCNN Mat：手动逐像素提取 RGB，跳过 Alpha
- NCNN Mat → Bitmap：手动逐像素写回 RGBA，Alpha 固定为 255
- 禁止使用 `ncnn::Mat::from_pixels(PIXEL_RGBA2RGB)` 等自动转换
- 归一化/反归一化在显式代码中完成

### 预加载 + 渐进式替换

1. 用户翻页时，`SRPreloadDispatcher` 触发后续 5 页的预加载
2. `SuperResolutionInterceptor` 优先查询预加载缓存
3. 缓存未命中时返回原图，后台异步处理
4. 处理完成后通过 `srResultFlow` 通知 UI 刷新

## 📁 项目结构

```
core/superresolution/
├── src/main/
│   ├── java/mihon/core/superresolution/
│   │   ├── SRModel.kt                    # 5 款模型枚举
│   │   ├── DenoiseLevel.kt               # 降噪等级 (OFF/LIGHT/STRONG)
│   │   ├── Quality.kt                    # 画质预设 (FAST/BALANCED/HIGH)
│   │   ├── SuperResolutionProcessor.kt   # 处理器接口
│   │   ├── RealESRGANProcessor.kt        # Real-ESRGAN/CUGAN 实现
│   │   ├── SuperResolutionManager.kt     # 核心引擎管理器
│   │   ├── SRPreloadDispatcher.kt        # 预加载调度器
│   │   ├── SRDiskCache.kt               # 磁盘缓存
│   │   ├── ModelManager.kt              # 模型资产管理
│   │   ├── VulkanHelper.kt              # Vulkan GPU 检测
│   │   ├── NativeLibraryStatus.kt       # 原生库状态
│   │   ├── NoOpProcessor.kt             # 降级处理器
│   │   ├── MangaBWPostProcessConfig.kt  # 黑白漫画后处理配置
│   │   ├── ColorMode.kt                 # 颜色模式
│   │   ├── SRStatusInfo.kt              # SR 状态数据
│   │   ├── SRStatusViewModel.kt         # SR 状态 ViewModel
│   │   ├── SRIndicatorPosition.kt       # 指示器位置
│   │   └── SRIndicatorDisplayMode.kt    # 指示器显示模式
│   ├── cpp/
│   │   ├── CMakeLists.txt               # CMake 构建配置
│   │   ├── realesrgan_jni.cpp           # JNI 桥接层
│   │   ├── realesrgan_wrapper.h/cpp     # NCNN 推理引擎
│   │   ├── manga_bw_postprocessor.h/cpp # 黑白漫画后处理
│   │   └── ncnn/                        # NCNN 预编译库 (需手动放置)
│   └── assets/models/                   # 内置模型文件 (需手动放置 .param/.bin)
```

## 🔨 构建指南

### 前置要求

- Android Studio Hedgehog+
- Android SDK 37
- NDK 27.0.12077973
- CMake 3.22.1+

### 模型文件（已内置）

✅ **所有 5 款 AI 超分模型已内置在 APK 中**，无需手动下载！

| 模型 | 倍率 | 大小 |
|------|------|------|
| Real-CUGAN 2x-conservative | x2 | ~2MB |
| Real-CUGAN 4x-conservative | x4 | ~2.2MB |
| Real-ESRGAN Anime Fast | x4 | ~1.5MB |
| Real-ESRGAN Anime+ | x4 | ~5MB |
| Real-ESRGAN General Fast | x4 | ~3MB |

### 准备 NCNN 库（仅需此步）

1. 运行自动准备脚本：
   ```bash
   # Linux/macOS
   ./prepare-build.sh

   # Windows
   prepare-build.bat
   ```

   脚本会自动从 GitHub 镜像下载 NCNN 预编译库并放置到正确位置。

   或者手动下载：
   1. 从 [NCNN Releases](https://github.com/nihui/ncnn/releases) 下载 Android 预编译库
   2. 将头文件放置到 `core/superresolution/src/main/cpp/ncnn/include/`
   3. 将静态库按 ABI 放置到 `core/superresolution/src/main/cpp/ncnn/lib/`

### 构建

```bash
./gradlew assembleDebug
```

## 📊 包体积估算

| 组件 | 大小 |
|------|------|
| NCNN 静态库 | ~1.5MB |
| librealesrgan-ncnn-vulkan.so | ~0.5MB |
| 内置模型 (5个, FP16) | ~13.7MB |
| C++ SR Engine + PostProcessor | ~0.3MB |
| Kotlin 层 | ~0.2MB |
| **总计增量** | **~16.2MB** |

## 🎮 使用说明

1. 在阅读器设置中启用「超分辨率」
2. 选择超分模型（推荐 Real-CUGAN 2x）
3. 选择降噪等级（轻度/强度/关闭）
4. 选择画质预设（快速/均衡/高质量）
5. 翻页阅读，SR 自动处理并替换

### 自动模型选择

| 缩放 | 降噪 | 画质 | 自动选择模型 |
|------|------|------|-------------|
| x2 | 任意 | — | Real-CUGAN 2x-conservative |
| x4 | 关闭/轻度 | 快速/均衡 | Real-ESRGAN Anime Fast |
| x4 | 关闭/轻度 | 高质量 | Real-ESRGAN Anime+ |
| x4 | 强度 | — | Real-ESRGAN General Fast |

## 📜 开发计划

| 阶段 | 内容 | 状态 |
|------|------|------|
| Phase 1 | C++ 推理引擎: NCNN 集成 + Real-CUGAN 跑通 | ✅ |
| Phase 2 | RealESRGANProcessor + SRModel 修改 + 引擎替换 | ✅ |
| Phase 3 | 黑白漫画后处理管线 (manga_bw_postprocessor) | ✅ |
| Phase 4 | 预加载调度器 + Interceptor 重构 + 渐进式替换 | ✅ |
| Phase 5 | 设置页面更新 + 模型适配 | ✅ |
| Phase 6 | 性能优化 + 磁盘缓存 + 测试 | ✅ |

## 🙏 致谢

- [Mihon](https://github.com/mihonapp/mihon) — 开源漫画阅读器框架
- [NCNN](https://github.com/nihui/ncnn) — 高性能神经网络推理框架
- [Real-ESRGAN](https://github.com/xinntao/Real-ESRGAN) — 超分辨率模型
- [Real-CUGAN](https://github.com/nihui/realcugan-ncnn-vulkan) — 超分辨率模型
- [mihon-super-resolution](https://github.com/timyang2005/mihon-super-resolution) — 前代项目 (Anime4K)

## 📄 许可证

本项目基于 [Mihon](https://github.com/mihonapp/mihon) 修改，遵循其原始许可证。
