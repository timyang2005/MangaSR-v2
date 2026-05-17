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

    int pixel_count = in_w * in_h;

    ncnn::Mat inimage(in_w, in_h, 3);
    float* in_data = static_cast<float*>(inimage.data);

    const unsigned char* rgba_data = static_cast<const unsigned char*>(pixels);
    for (int i = 0; i < pixel_count; i++) {
        in_data[i * 3 + 0] = rgba_data[i * 4 + 0] / 255.0f;
        in_data[i * 3 + 1] = rgba_data[i * 4 + 1] / 255.0f;
        in_data[i * 3 + 2] = rgba_data[i * 4 + 2] / 255.0f;
    }

    AndroidBitmap_unlockPixels(env, input_bitmap);

    ncnn::Mat outimage;
    bool success = wrapper->process(inimage, outimage);

    if (!success || outimage.empty()) {
        LOGE("RealESRGAN process failed");
        return nullptr;
    }

    jclass bitmap_class = env->FindClass("android/graphics/Bitmap");
    jclass config_class = env->FindClass("android/graphics/Bitmap$Config");

    jstring config_name = env->NewStringUTF("ARGB_8888");
    jmethodID value_of = env->GetStaticMethodID(config_class, "valueOf",
        "(Ljava/lang/String;)Landroid/graphics/Bitmap$Config;");
    jobject config = env->CallStaticObjectMethod(config_class, value_of, config_name);

    jmethodID create_bitmap = env->GetStaticMethodID(bitmap_class, "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jobject output_bitmap = env->CallStaticObjectMethod(bitmap_class, create_bitmap,
        out_w, out_h, config);

    void *out_pixels;
    lock_result = AndroidBitmap_lockPixels(env, output_bitmap, &out_pixels);
    if (lock_result != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("Failed to lock output bitmap pixels");
        env->DeleteLocalRef(config_name);
        env->DeleteLocalRef(config);
        env->DeleteLocalRef(bitmap_class);
        env->DeleteLocalRef(config_class);
        return nullptr;
    }

    int out_pixel_count = out_w * out_h;
    unsigned char* out_rgba = static_cast<unsigned char*>(out_pixels);
    const float* out_data = static_cast<const float*>(outimage.data);

    for (int i = 0; i < out_pixel_count; i++) {
        float r = out_data[i * 3 + 0] * 255.0f;
        float g = out_data[i * 3 + 1] * 255.0f;
        float b = out_data[i * 3 + 2] * 255.0f;
        out_rgba[i * 4 + 0] = static_cast<unsigned char>(std::max(0.0f, std::min(255.0f, r)));
        out_rgba[i * 4 + 1] = static_cast<unsigned char>(std::max(0.0f, std::min(255.0f, g)));
        out_rgba[i * 4 + 2] = static_cast<unsigned char>(std::max(0.0f, std::min(255.0f, b)));
        out_rgba[i * 4 + 3] = 255;
    }

    if (gray_levels > 0) {
        processMangaBW(out_rgba, out_w, out_h, gray_levels, density_correction == JNI_TRUE);
    }

    AndroidBitmap_unlockPixels(env, output_bitmap);

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
