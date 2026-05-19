#include "realesrgan_wrapper.h"
#include <android/log.h>
#include <algorithm>
#include <cstring>
#include <unistd.h>
#include <vector>
#include <cmath>

#define LOG_TAG "RealESRGANJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

int RealESRGANWrapper::calculateOptimalTileSize() {
    uint32_t available_mb = 0;

    long page_size = sysconf(_SC_PAGESIZE);
    long phys_pages = sysconf(_SC_PHYS_PAGES);
    uint32_t total_ram_mb = 0;
    if (page_size > 0 && phys_pages > 0) {
        total_ram_mb = static_cast<uint32_t>(
            (static_cast<long long>(phys_pages) * page_size) / (1024LL * 1024LL)
        );
    }
    LOGI("Total RAM: %u MB", total_ram_mb);

    uint32_t safe_ram_mb = total_ram_mb / 5;
    available_mb = safe_ram_mb;
    LOGI("Effective available (RAM/5): %u MB", available_mb);

    int optimal;
    if (available_mb >= 2500) {
        optimal = 400;
    } else if (available_mb >= 1200) {
        optimal = 200;
    } else if (available_mb >= 500) {
        optimal = 100;
    } else {
        optimal = 64;
    }

    float bytes_per_pixel = useFp16 ? 2.0f : 4.0f;
    float tile_input_mb = (optimal * optimal * 3 * bytes_per_pixel) / (1024.0f * 1024.0f);
    float intermediate_multiplier = (modelType == "realcugan") ? 20.0f : 40.0f;
    float tile_total_mb = tile_input_mb * (1.0f + scale * scale) * intermediate_multiplier;

    if (tile_total_mb > available_mb * 0.8f) {
        LOGI("tile_size=%d needs ~%.0f MB, exceeds 80%% of available %u MB, reducing",
             optimal, tile_total_mb, available_mb);
        while (optimal > 64) {
            optimal = (optimal == 400) ? 200 : (optimal == 200) ? 100 : 64;
            tile_input_mb = (optimal * optimal * 3 * bytes_per_pixel) / (1024.0f * 1024.0f);
            tile_total_mb = tile_input_mb * (1.0f + scale * scale) * intermediate_multiplier;
            if (tile_total_mb <= available_mb * 0.8f) break;
        }
    }

    LOGI("Optimal tile_size: %d (available=%u MB, model=%s, scale=%d, fp16=%d)",
         optimal, available_mb, modelType.c_str(), scale, useFp16);
    return optimal;
}

void RealESRGANWrapper::markInvalid() {
    is_valid.store(false, std::memory_order_release);
}

void RealESRGANWrapper::waitForIdle() {
    std::unique_lock<std::mutex> lock(cv_mutex);
    cv.wait_for(lock, std::chrono::seconds(10), [this]() {
        return active_processes.load(std::memory_order_acquire) == 0;
    });
}

bool RealESRGANWrapper::load(const char* param_path, const char* model_path, int gpu_id, const char* model_type, int initial_scale) {
    gpuid = gpu_id;
    modelType = model_type ? model_type : "realesrgan";
    scale = initial_scale > 0 ? initial_scale : 2;
    lastScale = scale;

    if (gpuid >= 0) {
        net.opt.use_vulkan_compute = true;
        int device_id = (gpuid < ncnn::get_gpu_count()) ? gpuid : ncnn::get_default_gpu_index();
        net.set_vulkan_device(device_id);
    }

    net.opt.use_fp16_storage = useFp16;
    net.opt.use_fp16_arithmetic = useFp16;

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

    tilesize = calculateOptimalTileSize();

    loaded = true;
    LOGI("Model loaded: type=%s, param=%s, model=%s, gpuid=%d, fp16=%d, scale=%d, tilesize=%d",
         modelType.c_str(), param_path, model_path, gpuid, useFp16, scale, tilesize);
    return true;
}

bool RealESRGANWrapper::process(const ncnn::Mat& inimage, ncnn::Mat& outimage) {
    active_processes.fetch_add(1, std::memory_order_acq_rel);
    if (!is_valid.load(std::memory_order_acquire)) {
        active_processes.fetch_sub(1, std::memory_order_acq_rel);
        cv.notify_all();
        LOGE("Wrapper invalid, cannot process");
        return false;
    }

    if (!loaded) {
        active_processes.fetch_sub(1, std::memory_order_acq_rel);
        cv.notify_all();
        LOGE("Model not loaded");
        return false;
    }

    if (scale != lastScale) {
        LOGI("Scale changed from %d to %d, recalculating tile_size", lastScale, scale);
        tilesize = calculateOptimalTileSize();
        lastScale = scale;
    }

    int w = inimage.w;
    int h = inimage.h;
    int out_w = w * scale;
    int out_h = h * scale;
    int tile_size = tilesize > 0 ? tilesize : 200;
    constexpr int prepadding = 10;

    outimage.create(out_w, out_h, 3);
    if (outimage.empty()) {
        LOGE("Failed to create output Mat");
        active_processes.fetch_sub(1, std::memory_order_acq_rel);
        { std::lock_guard<std::mutex> lock(cv_mutex); } cv.notify_all();
        return false;
    }

    // Official NCNN tiling: ceil division for tile count, prepadding for boundary context
    int xtiles = (w + tile_size - 1) / tile_size;
    int ytiles = (h + tile_size - 1) / tile_size;

    if (xtiles == 1 && ytiles == 1) {
        // Whole image fits in one tile — run inference directly
        ncnn::Mat out_tile;
        ncnn::Extractor ex = net.create_extractor();
        const char* input_blob = (modelType == "realcugan") ? "in0" : "data";
        const char* output_blob = (modelType == "realcugan") ? "out0" : "output";
        ex.input(input_blob, inimage);
        ex.extract(output_blob, out_tile);

        if (out_tile.empty()) {
            LOGE("Inference failed");
            active_processes.fetch_sub(1, std::memory_order_acq_rel);
            { std::lock_guard<std::mutex> lock(cv_mutex); } cv.notify_all();
            return false;
        }

        for (int c = 0; c < 3; c++) {
            for (int y = 0; y < out_h; y++) {
                const float* src_row = static_cast<const float*>(out_tile.channel(c).row(y));
                float* dst_row = static_cast<float*>(outimage.channel(c).row(y));
                memcpy(dst_row, src_row, out_w * sizeof(float));
            }
        }
        LOGI("Process complete (no tiling): %dx%d -> %dx%d", w, h, out_w, out_h);
        active_processes.fetch_sub(1, std::memory_order_acq_rel);
        { std::lock_guard<std::mutex> lock(cv_mutex); } cv.notify_all();
        return true;
    }

    const char* input_blob = (modelType == "realcugan") ? "in0" : "data";
    const char* output_blob = (modelType == "realcugan") ? "out0" : "output";

    int total_tiles = xtiles * ytiles;
    int processed = 0;

    for (int yi = 0; yi < ytiles; yi++) {
        for (int xi = 0; xi < xtiles; xi++) {
            if (!is_valid.load(std::memory_order_acquire)) {
                LOGE("Wrapper invalidated during tiling, aborting");
                active_processes.fetch_sub(1, std::memory_order_acq_rel);
                { std::lock_guard<std::mutex> lock(cv_mutex); } cv.notify_all();
                return false;
            }

            // Input tile: crop with prepadding for boundary context
            int tile_x0 = std::max(xi * tile_size - prepadding, 0);
            int tile_y0 = std::max(yi * tile_size - prepadding, 0);
            int tile_x1 = std::min((xi + 1) * tile_size + prepadding, w);
            int tile_y1 = std::min((yi + 1) * tile_size + prepadding, h);
            int tile_w = tile_x1 - tile_x0;
            int tile_h = tile_y1 - tile_y0;

            // Output region: non-overlapping tiles
            int out_x0 = xi * tile_size * scale;
            int out_y0 = yi * tile_size * scale;
            int out_x1 = std::min((xi + 1) * tile_size, w) * scale;
            int out_y1 = std::min((yi + 1) * tile_size, h) * scale;
            int out_tile_w = out_x1 - out_x0;
            int out_tile_h = out_y1 - out_y0;

            // Copy input tile from image
            ncnn::Mat in_tile(tile_w, tile_h, 3);
            for (int c = 0; c < 3; c++) {
                for (int y = 0; y < tile_h; y++) {
                    const float* src_row = static_cast<const float*>(inimage.channel(c).row(tile_y0 + y));
                    float* dst_row = static_cast<float*>(in_tile.channel(c).row(y));
                    memcpy(dst_row, src_row + tile_x0, tile_w * sizeof(float));
                }
            }

            // Run inference with fresh Extractor per tile (official NCNN pattern)
            ncnn::Mat out_tile;
            {
                ncnn::Extractor ex = net.create_extractor();
                ex.input(input_blob, in_tile);
                int ret = ex.extract(output_blob, out_tile);
                if (ret != 0 || out_tile.empty()) {
                    LOGE("Inference failed for tile (%d,%d), ret=%d", xi, yi, ret);
                    active_processes.fetch_sub(1, std::memory_order_acq_rel);
                    { std::lock_guard<std::mutex> lock(cv_mutex); } cv.notify_all();
                    return false;
                }
            }

            // Offset within out_tile where valid (non-padded) region starts
            int prepad_x = (xi * tile_size - tile_x0) * scale;
            int prepad_y = (yi * tile_size - tile_y0) * scale;

            // Copy valid region to output
            for (int c = 0; c < 3; c++) {
                for (int y = 0; y < out_tile_h; y++) {
                    int dst_y = out_y0 + y;
                    if (dst_y >= out_h) continue;
                    const float* src_row = static_cast<const float*>(out_tile.channel(c).row(prepad_y + y));
                    float* dst_row = static_cast<float*>(outimage.channel(c).row(dst_y));
                    for (int x = 0; x < out_tile_w; x++) {
                        int dst_x = out_x0 + x;
                        if (dst_x >= out_w) break;
                        dst_row[dst_x] = src_row[prepad_x + x];
                    }
                }
            }

            processed++;
            if (processed % 10 == 0 || processed == total_tiles) {
                LOGI("Tiling progress: %d/%d tiles processed", processed, total_tiles);
            }
        }
    }

    LOGI("Process complete: %dx%d -> %dx%d (tiles: %dx%d, tilesize=%d)",
         w, h, out_w, out_h, xtiles, ytiles, tile_size);
    active_processes.fetch_sub(1, std::memory_order_acq_rel);
    { std::lock_guard<std::mutex> lock(cv_mutex); } cv.notify_all();
    return true;
}
