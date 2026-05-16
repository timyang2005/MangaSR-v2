# MangaSR v2 构建指南

## 前置要求

- Android Studio Hedgehog+ 或 Android SDK Command Line Tools
- Android SDK 37
- NDK 27.0.12077973
- CMake 3.22.1+
- Gradle 8.7+

## 第一步：下载构建依赖

由于 GitHub 网络访问限制，请使用提供的脚本下载所有依赖：

### Linux / macOS

```bash
chmod +x prepare-build.sh
./prepare-build.sh
```

### Windows

```batch
prepare-build.bat
```

## 手动下载（可选）

如果自动脚本下载失败，请手动执行以下步骤：

### 1. 下载 NCNN Android Vulkan 库

访问 [NCNN Releases](https://github.com/nihui/ncnn/releases) 下载：

```
ncnn-20250915-android-vulkan.zip
```

解压后将文件放置到：

```
core/superresolution/src/main/cpp/ncnn/
├── include/      # 头文件
└── lib/         # 静态库 (armeabi-v7a, arm64-v8a, x86_64)
```

### 2. 下载 Real-CUGAN 模型

从 [realcugan-ncnn-vulkan-android-pretrained-models](https://github.com/nihui/realcugan-ncnn-vulkan-android-pretrained-models) 下载：

| 模型 | 文件 |
|------|------|
| 2x-conservative | `realcugan-2x-conservative.param`<br>`realcugan-2x-conservative.bin` |
| 4x-conservative | `realcugan-4x-conservative.param`<br>`realcugan-4x-conservative.bin` |

放置到 `core/superresolution/src/main/assets/models/` 对应目录。

### 3. 下载 Real-ESRGAN 模型

从 [realcugan-ncnn-vulkan-android-pretrained-models](https://github.com/nihui/realcugan-ncnn-vulkan-android-pretrained-models) 下载：

| 模型 | 文件 |
|------|------|
| anime-fast | `anime-fast.param`<br>`anime-fast.bin` |
| anime-plus | `anime-plus.param`<br>`anime-plus.bin` |
| general-fast | `general-fast.param`<br>`general-fast.bin` |

## 目录结构

下载完成后，目录结构应如下：

```
core/superresolution/src/main/
├── cpp/ncnn/
│   ├── include/
│   │   ├── ncnn/
│   │   │   ├── mat.h
│   │   │   ├── net.h
│   │   │   ├── gpu.h
│   │   │   └── ...
│   │   └── vulkan/
│   │       └── ...
│   └── lib/
│       ├── armeabi-v7a/
│       │   └── libncnn.a
│       ├── arm64-v8a/
│       │   └── libncnn.a
│       └── x86_64/
│           └── libncnn.a
└── assets/models/
    ├── realcugan-2x-conservative/
    │   ├── realcugan-2x-conservative.param
    │   └── realcugan-2x-conservative.bin
    ├── realcugan-4x-conservative/
    │   ├── realcugan-4x-conservative.param
    │   └── realcugan-4x-conservative.bin
    ├── realesrgan-anime-fast/
    │   ├── anime-fast.param
    │   └── anime-fast.bin
    ├── realesrgan-anime-plus/
    │   ├── anime-plus.param
    │   └── anime-plus.bin
    └── realesrgan-general-fast/
        ├── general-fast.param
        └── general-fast.bin
```

## 构建项目

```bash
./gradlew assembleDebug
```

## 验证构建

构建成功后，APK 输出位置：

```
app/build/outputs/apk/debug/app-debug.apk
```

## 常见问题

### Q: NCNN 下载失败怎么办？

A: 尝试使用国内镜像或代理，或从以下地址下载：
- Gitee 镜像: https://gitee.com/nihui/ncnn
- 腾讯云 COS: 部分预编译库托管在腾讯云

### Q: 模型文件在哪里？

A: 模型的 `.param` 和 `.bin` 文件在首次运行时会被从 APK assets 目录复制到应用私有目录。

### Q: 缺少 NDK 支持怎么办？

A: 在 Android Studio 中通过 SDK Manager 安装 NDK 27.0.12077973。

### Q: CMake 版本过低？

A: 更新 CMake 或在 Android Studio 中安装更新版本。
