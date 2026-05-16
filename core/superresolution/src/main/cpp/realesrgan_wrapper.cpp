#include "realesrgan_wrapper.h"
#include <android/log.h>
#include <algorithm>
#include <cstring>

#define LOG_TAG "RealESRGANJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

bool RealESRGANWrapper::load(const char* param_path, const char* model_path, int gpu_id, const char* model_type) {
    gpuid = gpu_id;
    modelType = model_type ? model_type : "realesrgan";

    if (gpuid >= 0) {
        net.opt.use_vulkan_compute = true;
        int device_id = (gpuid < ncnn::get_gpu_count()) ? gpuid : ncnn::get_default_gpu_index();
        net.set_vulkan_device(device_id);
    }
    net.opt.use_fp16_storage = true;
    net.opt.use_fp16_arithmetic = true;

    int ret = net.load_param(param_path);
    if (ret != 0) {
        LOGE("Failed to load param: %s", param_path);
        return false;
    }

    ret = net.load_model(model_path);
    if (ret != 0) {
        LOGE("Failed to load model: %s", model_path);
        return false;
    }

    loaded = true;
    LOGI("Model loaded: type=%s, param=%s, model=%s, gpuid=%d", modelType.c_str(), param_path, model_path, gpuid);
    return true;
}

bool RealESRGANWrapper::process(ncnn::Mat inimage, ncnn::Mat& outimage) {
    if (!loaded) {
        LOGE("Model not loaded");
        return false;
    }

    int w = inimage.w;
    int h = inimage.h;
    int out_w = w * scale;
    int out_h = h * scale;

    outimage.create(out_w, out_h, 3);
    if (outimage.empty()) {
        LOGE("Failed to create output Mat");
        return false;
    }

    memset(outimage.data, 0, out_w * out_h * 3 * sizeof(float));

    int tile_size = tilesize > 0 ? tilesize : 200;
    int overlap = 8;

    int xtiles = (w + tile_size - 1) / tile_size;
    int ytiles = (h + tile_size - 1) / tile_size;

    ncnn::Mat overlap_count;
    overlap_count.create(out_w, out_h, 3);
    memset(overlap_count.data, 0, out_w * out_h * 3 * sizeof(float));

    for (int yi = 0; yi < ytiles; yi++) {
        for (int xi = 0; xi < xtiles; xi++) {
            int x0 = xi * tile_size;
            int y0 = yi * tile_size;
            int x1 = std::min(x0 + tile_size, w);
            int y1 = std::min(y0 + tile_size, h);

            int tile_w = x1 - x0;
            int tile_h = y1 - y0;

            float* rgb_data = new float[tile_w * tile_h * 3];

            for (int y = 0; y < tile_h; y++) {
                for (int x = 0; x < tile_w; x++) {
                    int src_y = y0 + y;
                    int src_x = x0 + x;
                    const float* src_row = (const float*)inimage.data + src_y * w * 3;
                    int src_idx = src_x * 3;
                    int dst_idx = (y * tile_w + x) * 3;
                    rgb_data[dst_idx + 0] = src_row[src_idx + 0];
                    rgb_data[dst_idx + 1] = src_row[src_idx + 1];
                    rgb_data[dst_idx + 2] = src_row[src_idx + 2];
                }
            }

            ncnn::Mat in_tile(tile_w, tile_h, 3);
            memcpy(in_tile.data, rgb_data, tile_w * tile_h * 3 * sizeof(float));
            delete[] rgb_data;

            ncnn::Mat out_tile;
            ncnn::Extractor ex = net.create_extractor();
            const char* input_blob = (modelType == "realcugan") ? "in0" : "data";
            const char* output_blob = (modelType == "realcugan") ? "out0" : "output";
            ex.input(input_blob, in_tile);
            ex.extract(output_blob, out_tile);

            if (out_tile.empty()) {
                LOGE("Inference failed for tile (%d,%d)", xi, yi);
                return false;
            }

            int out_tile_w = out_tile.w;
            int out_tile_h = out_tile.h;

            int out_x0 = x0 * scale;
            int out_y0 = y0 * scale;

            for (int y = 0; y < out_tile_h; y++) {
                int dst_y = out_y0 + y;
                if (dst_y >= out_h) break;
                for (int x = 0; x < out_tile_w; x++) {
                    int dst_x = out_x0 + x;
                    if (dst_x >= out_w) break;

                    const float* src_row = (const float*)out_tile.data + y * out_tile_w * 3;
                    float* dst_row = (float*)outimage.data + dst_y * out_w * 3;
                    float* cnt_row = (float*)overlap_count.data + dst_y * out_w * 3;

                    int src_idx = x * 3;
                    int dst_idx = dst_x * 3;

                    dst_row[dst_idx + 0] += src_row[src_idx + 0];
                    dst_row[dst_idx + 1] += src_row[src_idx + 1];
                    dst_row[dst_idx + 2] += src_row[src_idx + 2];
                    cnt_row[dst_idx + 0] += 1.0f;
                    cnt_row[dst_idx + 1] += 1.0f;
                    cnt_row[dst_idx + 2] += 1.0f;
                }
            }
        }
    }

    for (int i = 0; i < out_w * out_h * 3; i++) {
        if (overlap_count[i] > 1.0f) {
            outimage[i] /= overlap_count[i];
        }
    }

    LOGI("Process complete: %dx%d -> %dx%d", w, h, out_w, out_h);
    return true;
}
