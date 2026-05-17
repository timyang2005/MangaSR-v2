package mihon.core.superresolution

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import logcat.LogPriority
import logcat.logcat

object VulkanHelper {

    fun isVulkanSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val pm = context.packageManager
        val hasVulkan = pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION) ||
            pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
        logcat(LogPriority.INFO) { "VulkanHelper: FEATURE_VULKAN_HARDWARE_VERSION=${pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)}, FEATURE_VULKAN_HARDWARE_LEVEL=${pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)}" }
        return hasVulkan
    }

    fun getGpuCount(): Int {
        return try {
            val count = nativeGetGpuCount()
            logcat(LogPriority.INFO) { "VulkanHelper: nativeGetGpuCount=$count" }
            count
        } catch (e: UnsatisfiedLinkError) {
            logcat(LogPriority.ERROR) { "VulkanHelper: nativeGetGpuCount failed (native lib not loaded): ${e.message}" }
            0
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "VulkanHelper: nativeGetGpuCount failed: ${e.message}" }
            0
        }
    }

    fun getDeviceInfo(gpuid: Int): String {
        return try {
            nativeGetDeviceInfo(gpuid)
        } catch (_: UnsatisfiedLinkError) {
            "Unknown"
        }
    }

    private external fun nativeGetGpuCount(): Int
    private external fun nativeGetDeviceInfo(gpuid: Int): String
}
