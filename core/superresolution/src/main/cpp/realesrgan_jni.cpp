#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <string>
#include <cstring>
#include <algorithm>
#include <ncnn/net.h>
#include <ncnn/gpu.h>

#include "realesrgan_wrapper.h"
#include "manga_bw_postprocessor.h"

#define LOG_TAG "RealESRGANJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_mihon_core_superresolution_RealESRGANProcessor_nativeInit(
    JNIEnv *env, jobject thiz,
    jstring param_path, jstring bin_path, jint gpuid, jstring model_type) {

    auto* wrapper = new RealESRGANWrapper();

    const char *param = env->GetStringUTFChars(param_path, nullptr);
    const char *model = env->GetStringUTFChars(bin_path, nullptr);
    const char *type = env->GetStringUTFChars(model_type, nullptr);

    bool result = wrapper->load(param, model, gpuid, type);

    env->ReleaseStringUTFChars(param_path, param);
    env->ReleaseStringUTFChars(bin_path, model);
    env->ReleaseStringUTFChars(model_type, type);

    if (!result) {
        delete wrapper;
        LOGE("Failed to initialize RealESRGAN");
        return 0;
    }

    LOGI("RealESRGAN initialized: gpuid=%d, type=%s", gpuid, wrapper->modelType.c_str());
    return reinterpret_cast<jlong>(wrapper);
}

JNIEXPORT jobject JNICALL
Java_mihon_core_superresolution_RealESRGANProcessor_nativeProcess(
    JNIEnv *env, jobject thiz,
    jlong handle, jobject input_bitmap,
    jint scale, jfloat denoise_strength,
    jint gray_levels, jboolean density_correction) {

    if (handle == 0) {
        LOGE("Invalid handle");
        return nullptr;
    }

    auto* wrapper = reinterpret_cast<RealESRGANWrapper*>(handle);
    wrapper->scale = scale;

    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, input_bitmap, &info);

    int in_w = info.width;
    int in_h = info.height;
    int out_w = in_w * scale;
    int out_h = in_h * scale;

    void *pixels;
    int lock_result = AndroidBitmap_lockPixels(env, input_bitmap, &pixels);
    if (lock_result != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("Failed to lock input bitmap pixels");
        return nullptr;
    }

    // 正确使用 ncnn::Mat 的 channel().row() API，不使用 ncnn::Mat::from_pixels
    ncnn::Mat inimage(in_w, in_h, 3);
    const unsigned char* rgba_data = static_cast<const unsigned char*>(pixels);
    
    for (int y = 0; y < in_h; y++) {
        const unsigned char* rgba_row = rgba_data + y * in_w * 4;
        float* r_row = static_cast<float*>(inimage.channel(0).row(y));
        float* g_row = static_cast<float*>(inimage.channel(1).row(y));
        float* b_row = static_cast<float*>(inimage.channel(2).row(y));
        
        for (int x = 0; x < in_w; x++) {
            int idx = x * 4;
            r_row[x] = rgba_row[idx + 0] / 255.0f;
            g_row[x] = rgba_row[idx + 1] / 255.0f;
            b_row[x] = rgba_row[idx + 2] / 255.0f;
        }
    }

    AndroidBitmap_unlockPixels(env, input_bitmap);

    ncnn::Mat outimage;
    bool success = wrapper->process(inimage, outimage);

    if (!success || outimage.empty()) {
        LOGE("RealESRGAN process failed");
        return nullptr;
    }

    // 查找类和方法 - 保持 JNI 异常检查
    jclass bitmap_class = env->FindClass("android/graphics/Bitmap");
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        LOGE("Failed to find Bitmap class");
        return nullptr;
    }

    jclass config_class = env->FindClass("android/graphics/Bitmap$Config");
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        LOGE("Failed to find Bitmap.Config class");
        return nullptr;
    }

    jstring config_name = env->NewStringUTF("ARGB_8888");
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        LOGE("Failed to create config name string");
        return nullptr;
    }

    jmethodID value_of = env->GetStaticMethodID(config_class, "valueOf",
        "(Ljava/lang/String;)Landroid/graphics/Bitmap$Config;");
    if (env->ExceptionCheck() || value_of == nullptr) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        env->DeleteLocalRef(config_name);
        LOGE("Failed to find valueOf method");
        return nullptr;
    }

    jobject config = env->CallStaticObjectMethod(config_class, value_of, config_name);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        env->DeleteLocalRef(config_name);
        LOGE("Failed to call valueOf");
        return nullptr;
    }

    jmethodID create_bitmap = env->GetStaticMethodID(bitmap_class, "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    if (env->ExceptionCheck() || create_bitmap == nullptr) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        env->DeleteLocalRef(config_name);
        env->DeleteLocalRef(config);
        LOGE("Failed to find createBitmap method");
        return nullptr;
    }

    jobject output_bitmap = env->CallStaticObjectMethod(bitmap_class, create_bitmap,
        out_w, out_h, config);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        env->DeleteLocalRef(config_name);
        env->DeleteLocalRef(config);
        LOGE("Failed to create output bitmap");
        return nullptr;
    }

    void *out_pixels;
    lock_result = AndroidBitmap_lockPixels(env, output_bitmap, &out_pixels);
    if (lock_result != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("Failed to lock output bitmap pixels");
        env->DeleteLocalRef(config_name);
        env->DeleteLocalRef(config);
        env->DeleteLocalRef(bitmap_class);
        env->DeleteLocalRef(config_class);
        env->DeleteLocalRef(output_bitmap);
        return nullptr;
    }

    // 正确使用 ncnn::Mat 的 channel().row() API 写回数据，不使用 to_pixels
    unsigned char* out_rgba = static_cast<unsigned char*>(out_pixels);
    for (int y = 0; y < out_h; y++) {
        const float* r_row = static_cast<const float*>(outimage.channel(0).row(y));
        const float* g_row = static_cast<const float*>(outimage.channel(1).row(y));
        const float* b_row = static_cast<const float*>(outimage.channel(2).row(y));
        unsigned char* rgba_row = out_rgba + y * out_w * 4;
        
        for (int x = 0; x < out_w; x++) {
            float r = r_row[x] * 255.0f;
            float g = g_row[x] * 255.0f;
            float b = b_row[x] * 255.0f;
            
            rgba_row[x * 4 + 0] = static_cast<unsigned char>(std::max(0.0f, std::min(255.0f, r)));
            rgba_row[x * 4 + 1] = static_cast<unsigned char>(std::max(0.0f, std::min(255.0f, g)));
            rgba_row[x * 4 + 2] = static_cast<unsigned char>(std::max(0.0f, std::min(255.0f, b)));
            rgba_row[x * 4 + 3] = 255;
        }
    }

    if (gray_levels > 0) {
        processMangaBW(static_cast<unsigned char*>(out_pixels), out_w, out_h, gray_levels, density_correction == JNI_TRUE);
    }

    AndroidBitmap_unlockPixels(env, output_bitmap);

    // 清理本地引用
    env->DeleteLocalRef(config_name);
    env->DeleteLocalRef(config);
    env->DeleteLocalRef(bitmap_class);
    env->DeleteLocalRef(config_class);

    LOGI("Process complete: %dx%d -> %dx%d", in_w, in_h, out_w, out_h);
    return output_bitmap;
}

JNIEXPORT void JNICALL
Java_mihon_core_superresolution_RealESRGANProcessor_nativeRelease(
    JNIEnv *env, jobject thiz, jlong handle) {

    if (handle == 0) return;
    auto* wrapper = reinterpret_cast<RealESRGANWrapper*>(handle);
    delete wrapper;
    LOGI("RealESRGAN released");
}

JNIEXPORT jint JNICALL
Java_mihon_core_superresolution_VulkanHelper_nativeGetGpuCount(
    JNIEnv *env, jobject thiz) {
    return ncnn::get_gpu_count();
}

JNIEXPORT jstring JNICALL
Java_mihon_core_superresolution_VulkanHelper_nativeGetDeviceInfo(
    JNIEnv *env, jobject thiz, jint gpuid) {
    if (gpuid < 0 || gpuid >= ncnn::get_gpu_count()) {
        return env->NewStringUTF("Unknown");
    }
    return env->NewStringUTF(ncnn::get_gpu_info(gpuid).device_name());
}

}
