# 批量超分队列设计文档

## 目标

允许用户选择已下载的漫画章节加入超分队列，在后台逐章处理并缓存，使后续阅读时 SR 结果 0 延迟可用，改善低端设备的阅读体验。

## 总览

| 组件 | 文件 | 职责 |
|------|------|------|
| 元数据 | `SRDiskCache._ch_*` | batch 完成时写入章节元数据，供管理页展示 |
| 队列处理器 | `SRQueueProcessor` | 逐章串行处理，进度通过 StateFlow 暴露 |
| 队列持久化 | SharedPreferences+JSON | 仅保存进行中进度，进程恢复时续跑 |
| Manga 详情页 | `MangaScreen + MangaBottomActionMenu` | 章节多选 → 加入队列，每行展示 SR 状态 |
| 设置页管理 | `SettingsReaderScreen` | 队列状态 + 已完成缓存管理（多选删除） |
| Icon | `drawable/ic_sr_sparkle_24dp.xml` | 章节 SR 状态指示 + 操作按钮 |

## 数据模型

### SRQueueItem

进行中队列条目，存 SharedPreferences：

```json
{
  "chapterId": 12345,
  "mangaId": 42,
  "mangaTitle": "呪術廻戦",
  "chapterName": "第256話",
  "sourceKey": 1234567890,
  "totalPages": 40,
  "processedPages": 12
}
```

### ChapterMetadata

batch 完成时写入 `sr_disk_cache/_ch_${chapterId}.json`：

```json
{
  "mangaId": 42,
  "mangaTitle": "呪術廻戦",
  "chapterName": "第256話",
  "pageCount": 40
}
```

## SRDiskCache 扩展

```kotlin
class SRDiskCache(private val cacheDir: File) {
    // 已存在
    fun get(key: String): Bitmap?
    fun put(key: String, bitmap: Bitmap)
    fun remove(key: String)
    fun clear()
    fun getUsage(): Pair<Int, Long>

    // 新增
    fun putChapterMetadata(chapterId: Long, meta: ChapterMetadata)
    fun getCompletedChapters(): List<ChapterMetadata>
    fun removeChapter(chapterId: Long)     // 删元数据 + 所有 page_${chapterId}_*
}
```

`removeChapter` 实现：

```kotlin
fun removeChapter(chapterId: Long) {
    cacheDir.listFiles { _, name ->
        name.startsWith("page_${chapterId}_") || name == "_ch_$chapterId.json"
    }?.forEach { it.delete() }
}
```

Eviction 应保留元数据文件（极小，可忽略不计）。

## 队列处理器：SRQueueProcessor

### 初始化

随 `SuperResolutionSync.start()` 一同启动，加载持久化的进行中队列，自动继续处理。

### 处理流程

```
出队一个 chapter
  ↓
DownloadProvider.findChapterDir() → 获取章节目录
  ↓
列出目录中所有图片文件（同 DownloadManager.buildPageList）
  ↓
逐页：
  1. read bitmap from file
  2. manager.process(input, versionAtStart)
  3. diskCache.put(cacheKey, srResult)
  4. 更新 processedPages
  ↓
全部页面完成 → diskCache.putChapterMetadata(chapterId, meta)
  ↓
从队列移除 → 持续 polling 下一个
```

### 并发

- 逐章串行（SR 是 GPU 密集操作，并行无收益）
- 单章内逐页串行（单页 1-15s，串行足够快，并行徒增内存）
- `Dispatchers.Default` 作用域

### 约束

处理中若用户切换模型 → 队列暂停（当前 `process()` 版本文本本检测会 return input）→ 模型稳定后自动恢复

## 漫画详情页

### 章节行 SR 状态图标

每行加载时检查 `diskCache.getCompletedChapters()` → 缓存到 `Map<Long, Boolean>` → `_ch_` 存在即显示绿色 sparkle。检查仅涉及文件是否存在（`File.exists()`），开销可忽略。

图标位置：下载图标左边（参见 `MangaChapterListItem.kt`）

### 底部操作栏

`MangaBottomActionMenu` 新增「超分」按钮（`ic_sr_sparkle`），规则：
- 选中章节中有任一 `chapter.downloadState != DOWNLOADED` → 置灰
- 点击后调 `queueProcessor.enqueue(chapters)` → Toast "已加入超分队列（N 章节）"

## 设置页队列管理

### 入口

`SettingsReaderScreen` SR 分组新增 TextPreference：

```
批量超分队列
┗ 副标题: "2 个处理中 · 5 个已完成"
```

### Dialog 内容

```
═ 进行中 ═
拽我入凡尘 第 24 话  ████████░░  30/40

═ 已完成（按漫画分组） ═
□ 呪術廻戦
   第 256 话 · 40 页
   第 257 话 · 38 页
□ 迷宫饭
   第 95 话 · 24 页

[删除选中] [清空全部]
```

- 进行中队列从 processor StateFlow 读取
- 已完成列表从 `diskCache.getCompletedChapters()` 实时扫描

### 删除操作

- "删除选中" → 对每个选中 chapterId 调 `diskCache.removeChapter(id)`
- "清空全部" → 保留进行中条目，仅清已完成

## 不需要做的

- 下载管理页入口（章节下载完即消失，无多选基础）
- WorkManager 集成（进程内队列足够）
- Room/SQLDelight 表（SharedPreferences 已经够用）
