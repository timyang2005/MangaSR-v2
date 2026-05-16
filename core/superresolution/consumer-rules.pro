-keep class com.tencent.ncnn.** { *; }
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class mihon.core.superresolution.RealESRGANProcessor { *; }
-keep class mihon.core.superresolution.VulkanHelper { *; }
