<p align="center">
  <h1 align="center">Mihon Super Resolution</h1>
  <p align="center">
    <a href="https://github.com/timyang2005/mihon-super-resolution/releases/latest"><img alt="GitHub release (latest by date)" src="https://img.shields.io/github/v/release/timyang2005/mihon-super-resolution?label=stable&logo=android&color=success"/></a>
    <a href="https://github.com/timyang2005/mihon-super-resolution/actions/workflows/build.yml"><img alt="Build" src="https://img.shields.io/github/actions/workflow/status/timyang2005/mihon-super-resolution/build.yml?logo=github"/></a>
    <a href="https://github.com/timyang2005/mihon-super-resolution/blob/main/LICENSE"><img alt="License" src="https://img.shields.io/github/license/timyang2005/mihon-super-resolution"/></a>
  </p>
  <p align="center">
    基于 <a href="https://github.com/mihonapp/mihon">Mihon</a> 的 AI 实时超分辨率漫画阅读器<br/>
    搭载 Real-CUGAN & Real-ESRGAN + NCNN Vulkan
  </p>
</p>

---

## 特性

- 🎨 **5 款内置 AI 模型** — Real-CUGAN (2x/4x) + Real-ESRGAN (Anime Fast/Plus, General Fast)
- ⚡ **Vulkan GPU 加速** — 基于 NCNN 推理框架，利用手机 GPU 实时处理
- 📦 **批量离线超分** — 选择已下载章节，后台批量处理并持久化
- 📖 **实时预加载** — 翻页时自动预加载前后 N 页，零等待阅读体验
- 🔄 **渐进式替换** — 先显示原图，超分完成后无缝替换
- 🖤 **黑白优化** — 灰度级量化 + Otsu 二值化 + 点阵密度校正
- 💾 **分级缓存** — 内存 LRU + WebP 磁盘缓存 + 批量结构化存储
- 🔧 **灵活配置** — 全局设置 / 单漫画独立设置 / 性能基准测试

## 下载

从 [Releases](https://github.com/timyang2005/mihon-super-resolution/releases/latest) 下载最新 APK 直接安装。

> **要求**：Android 11+ (SDK 30+)，设备需支持 Vulkan GPU。

## 模型

| 模型 | 倍率 | 大小 | 特点 |
|------|------|------|------|
| Real-CUGAN 2x-conservative | x2 | ~2MB | 速度最快，2x 对漫画已足够 |
| Real-CUGAN 4x-conservative | x4 | ~2.2MB | 快速 4x 放大 |
| Real-ESRGAN Anime Fast | x4 | ~1.5MB | 动漫专用，体积最小 |
| Real-ESRGAN Anime+ | x4 | ~5MB | 动漫增强，细节恢复最佳 |
| Real-ESRGAN General Fast | x4 | ~3MB | 通用模型，强降噪场景 |

## 构建

### 前置要求

- Android Studio Hedgehog+
- JDK 17
- Android SDK 35+
- NDK 27.0+

### 构建步骤

```bash
git clone https://github.com/timyang2005/mihon-super-resolution.git
cd mihon-super-resolution
./gradlew assembleDebug
```

所有构建依赖已内置（NCNN 预编译库 + 5 款 AI 模型）。

## 使用说明

### 实时超分

1. 阅读器设置 → 启用「超分辨率」
2. 选择模型（推荐 Real-CUGAN 2x）
3. 设置降噪等级和画质预设
4. 翻页阅读，SR 自动处理并替换

### 批量超分

1. 下载漫画章节
2. 在漫画详情页长按章节进入多选
3. 选中已下载章节 → 底部「超分」按钮
4. 后台自动处理，设置页可查看队列进度

### 性能测试

设置 → 运行 SR 性能测试 → 设备分级 + 自动推荐配置。

## 许可证

本项目基于 [Mihon](https://github.com/mihonapp/mihon) 修改，遵循其原始许可证。

## 致谢

- [Mihon](https://github.com/mihonapp/mihon) — 开源漫画阅读器框架
- [NCNN](https://github.com/nihui/ncnn) — 高性能神经网络推理框架
- [Real-ESRGAN](https://github.com/xinntao/Real-ESRGAN) — 超分辨率模型
- [Real-CUGAN](https://github.com/nihui/realcugan-ncnn-vulkan) — 超分辨率模型
