# SR 模块代码审查报告

**日期：** 2026-05-20
**审查范围：** `core/superresolution/` + 应用层 SR 集成代码
**分支：** `feat/benchmark-dynamic-scheduling`

---

## 优点

1. **架构清晰** — 关注点分离良好：`SuperResolutionManager` 核心编排，`RealESRGANProcessor` 封装 JNI，`SRPreloadDispatcher` 预加载，`SRDiskCache` 磁盘缓存，各司其职
2. **降级策略完善** — 三处关键失败（native 库缺失、初始化失败、Processor 创建失败）均有 `NoOpProcessor` 兜底，SR 模块不会导致应用崩溃
3. **缓存键统一** (`SuperResolutionManager.kt:238-241`) — 三调用方收敛到 `buildCacheKey()`，消除 key 格式漂移风险
4. **Stale model detection** (`SuperResolutionManager.kt:168-171, 185-187`) — `modelVersion` 版本号在 process 前后两次校验，防止并发切换模型时返回过时结果
5. **锁粒度合理** — `mutex` 序列化 `process` 调用、`cacheLock` 保护 `LinkedHashMap`、`jobsMutex` 保护 `processingJobs`

---

## 问题

### Critical（必须修复）

**1. `SRDiskCache.getFile()` 文件扩展名歧义**
- **文件：** `SRDiskCache.kt:63-65`
- **问题：** 同一 key 在不同 Android 版本上可能映射到 `.webp` 或 `.jpg` 两种文件名。缓存写入后，若设备从 Android 11+ 降级（不可能实际发生），或用户切换 ROM，已缓存的 `.webp` 文件再也无法被读取。更实际的风险是：`clear()` 或 `evictIfNeeded()` 遍历时，一个 key 的旧 `.jpg` 文件和新 `.webp` 文件同时存在，占用双倍空间。
- **修复：** 统一存储格式（仅用 WEBP），或文件名中嵌入扩展标识符。

**2. `SRDiskCache.clear()` 与 `evictIfNeeded()` 并发竞态**
- **文件：** `SRDiskCache.kt:57-60, 68-82`
- **问题：** `clear()` 删除所有文件时，`evictIfNeeded()` 可能在另一个协程中并行迭代 `listFiles()`。`clear()` 删除正在被 eviction 访问的文件可能导致 `FileNotFoundException` 或漏删。
- **修复：** 对缓存目录操作加锁（如 `synchronized(cacheDir)`），或使用 `AtomicBoolean` 标志阻止并发 eviction。

**3. `SRStatusInfo` 默认值语义错误**
- **文件：** `SRStatusViewModel.kt:9`
- **问题：** IDLE 状态携带 `REALCUGAN_2X_CONSERVATIVE` 具体模型引用。UI 层若未检查 status 直接展示 model，会误导用户。
- **修复：** IDLE 状态时模型字段设为 null（`SRStatusInfo` 需改为 `val model: SRModel?`）。

---

### Important（应该修复）

**1. 模型加载阻塞所有 process 请求**
- **文件：** `SuperResolutionManager.kt:132-207`
- **问题：** `mutex.withLock` 下，模型加载（首次 process 时）与其他 process 调用串行。加载期间大量并发请求排队等待。
- **建议：** 考虑将加载步骤移到锁外，或使用读写锁。

**2. `ReaderPageImageView` SR 轮询无 View 回收防护**
- **文件：** `ReaderPageImageView.kt:189-225`
- **问题：** `scheduleSrRefresh()` 的 Runnable 在页面被 ViewPager 回收后仍可能执行，引用已销毁的 `pageView`。`cancelSrRefresh()` 的调用链是否覆盖所有回收路径不明确。
- **建议：** Runnable 执行时检查 `isAttachedToWindow`。

**3. `SuperResolutionSync` 缺少异常堆栈**
- **文件：** `SuperResolutionSync.kt:59`
- **问题：** `logcat(LogPriority.ERROR) { "SR sync error: ${e.message}" }` 无 `e.asLog()`，丢失堆栈。
- **修复：** 改为 `e.asLog()`。

**4. 快速翻页时预加载风暴**
- **文件：** `SRPreloadDispatcher.kt:29-60`
- **问题：** 每次 `onPageChanged` 调用都会为当前页后 `window` 范围内所有页面发起预加载协程。快速连续翻页会导致大量并发预加载请求。
- **建议：** 添加去抖动（debounce）机制，或限制并发预加载数。

**5. `NativeLibraryStatus.isModelAvailable` 忽略 NoOp 回退**
- **文件：** `NativeLibraryStatus.kt:12-16`
- **问题：** 对非 Vulkan 模型直接返回 `true`，未检查系统是否已退化到 NoOp 模式。
- **建议：** 增加 `RealESRGANProcessor.nativeLibraryLoaded` 检查。

---

### Minor（可以改进）

1. **`SRCacheManager.clearDiskCache()` 每次都新建 `SRDiskCache` 实例** — `SRCacheManager.kt:40-49`。虽然功能正确，但与 `SRPreloadDispatcher` 持有的实例不一致。
2. **`SRStatusViewModel.onSRStartWithStartTime()` 无调用者** — `SRStatusViewModel.kt:22-30`。与 `onSRStart()` 仅 startTime 来源不同，可删除合并。
3. **`NoOpProcessor.model` 硬编码为默认值** — `NoOpProcessor.kt:8`。回退时无法反映实际尝试加载的模型。
4. **`VulkanHelper.getDeviceInfo()` 静默吞异常** — `VulkanHelper.kt:37-38`。返回 `"Unknown"` 无法区分 native 库未加载还是真的没有设备信息。
5. **`process()` 接口参数未使用** — `SuperResolutionProcessor.kt:15` 定义 `denoiseLevel` 参数，但 `RealESRGANProcessor.kt:40-44` 实现中未使用它（只用了 `denoiseStrength`）。

---

## 建议

### 测试覆盖
整个 SR 模块零测试。建议至少为以下添加：
- Cache key 生成与一致性
- 并发处理时 modelVersion 校验
- 磁盘缓存读写与 eviction 边界条件

### Eviction 排序逻辑可读性
`SRDiskCache.kt:73-78`：`sortByDescending` 后从末尾删除，逻辑正确但迭代方向增加阅读复杂度，建议正向遍历。

### 全局 Scope 生命周期
`SuperResolutionSync.kt:23` 使用 `CoroutineScope(SupervisorJob() + Dispatchers.Default)` 无生命周期关联，建议在应用适当生命周期回调中调用 `scope.cancel()`。

---

## 评估

**可以合并吗？** 修复后可以

**理由：** 模块架构合理，近期优化（日志、缓存键统一、模型加载时机）显著提升可维护性。Critical 问题（磁盘缓存并发、文件扩展名歧义）影响生产可靠性且修复成本低，建议先修。代码整体达到生产就绪水平。
