package mihon.core.superresolution

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object VulkanHelper {

    fun isVulkanSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)
    }

    fun getGpuCount(): Int {
        return try {
            nativeGetGpuCount()
        } catch (_: UnsatisfiedLinkError) {
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
