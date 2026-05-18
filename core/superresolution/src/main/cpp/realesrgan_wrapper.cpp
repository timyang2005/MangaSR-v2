#include "realesrgan_wrapper.h"
#include <android/log.h>
#include <algorithm>
#include <cstring>
#include <unistd.h>
#include <vector>

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
    if (!is_valid.load(std::memory_order_acquire)) {
        LOGE("Wrapper invalid, cannot process");
        return false;
    }

    if (!loaded) {
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

    if (w <= tile_size && h <= tile_size) {
        outimage.create(out_w, out_h, 3);
        if (outimage.empty()) {
            LOGE("Failed to create output Mat");
            return false;
        }

        ncnn::Mat out_tile;
        ncnn::Extractor ex = net.create_extractor();
        const char* input_blob = (modelType == "realcugan") ? "in0" : "data";
        const char* output_blob = (modelType == "realcugan") ? "out0" : "output";
        ex.input(input_blob, inimage);
        ex.extract(output_blob, out_tile);

        if (out_tile.empty()) {
            LOGE("Inference failed");
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
        return true;
    }

    int pad_w = ((w + tile_size - 1) / tile_size) * tile_size - w;
    int pad_h = ((h + tile_size - 1) / tile_size) * tile_size - h;
    int padded_w = w + pad_w;
    int padded_h = h + pad_h;
    int padded_out_w = padded_w * scale;
    int padded_out_h = padded_h * scale;

    ncnn::Mat padded_in(padded_w, padded_h, 3);
    for (int c = 0; c < 3; c++) {
        for (int y = 0; y < padded_h; y++) {
            float* dst_row = static_cast<float*>(padded_in.channel(c).row(y));
            for (int x = 0; x < padded_w; x++) {
                int px = std::min(x, w - 1);
                int py = std::min(y, h - 1);
                dst_row[x] = static_cast<float*>(inimage.channel(c).row(py))[px];
            }
        }
    }

    ncnn::Mat padded_out(padded_out_w, padded_out_h, 3);
    if (padded_out.empty()) {
        LOGE("Failed to create padded output Mat");
        return false;
    }

    for (int c = 0; c < 3; c++) {
        float* ch_data = static_cast<float*>(padded_out.channel(c).data);
        memset(ch_data, 0, padded_out_w * padded_out_h * sizeof(float));
    }

    int overlap = tile_size / 8;
    int xtiles = padded_w / tile_size;
    int ytiles = padded_h / tile_size;

    ncnn::Mat alpha;
    {
        alpha.create(tile_size, tile_size, 1);
        alpha.fill(1.f);

        for (int i = 0; i < overlap; i++) {
            float a = (float)(i + 1) / (float)(overlap + 1);

            for (int j = 0; j < tile_size; j++) {
                alpha.row(j)[i] *= a;
                alpha.row(j)[tile_size - 1 - i] *= a;
                alpha.row(i)[j] *= a;
                alpha.row(tile_size - 1 - i)[j] *= a;
            }
        }
    }

    for (int yi = 0; yi < ytiles; yi++) {
        for (int xi = 0; xi < xtiles; xi++) {
            int x0 = xi * tile_size;
            int y0 = yi * tile_size;

            ncnn::Mat in_tile(tile_size, tile_size, 3);
            for (int c = 0; c < 3; c++) {
                for (int y = 0; y < tile_size; y++) {
                    const float* src_row = static_cast<const float*>(padded_in.channel(c).row(y0 + y));
                    float* dst_row = static_cast<float*>(in_tile.channel(c).row(y));
                    memcpy(dst_row, src_row + x0, tile_size * sizeof(float));
                }
            }

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

            int out_x0 = x0 * scale;
            int out_y0 = y0 * scale;
            int out_tile_w = tile_size * scale;
            int out_tile_h = tile_size * scale;

            for (int c = 0; c < 3; c++) {
                for (int sy = 0; sy < out_tile_h; sy++) {
                    int dy = out_y0 + sy;
                    if (dy >= padded_out_h) continue;

                    const float* src_row = static_cast<const float*>(out_tile.channel(c).row(sy));
                    float* dst_row = static_cast<float*>(padded_out.channel(c).row(dy));

                    for (int sx = 0; sx < out_tile_w; sx++) {
                        int dx = out_x0 + sx;
                        if (dx >= padded_out_w) continue;

                        int alpha_x = sx / scale;
                        int alpha_y = sy / scale;
                        float alpha_val = alpha.row(alpha_y)[alpha_x];

                        dst_row[dx] = dst_row[dx] * (1.f - alpha_val) + src_row[sx] * alpha_val;
                    }
                }
            }
        }
    }

    outimage.create(out_w, out_h, 3);
    if (outimage.empty()) {
        LOGE("Failed to create output Mat");
        return false;
    }

    for (int c = 0; c < 3; c++) {
        for (int y = 0; y < out_h; y++) {
            const float* src_row = static_cast<const float*>(padded_out.channel(c).row(y));
            float* dst_row = static_cast<float*>(outimage.channel(c).row(y));
            memcpy(dst_row, src_row, out_w * sizeof(float));
        }
    }

    LOGI("Process complete: %dx%d -> %dx%d (tiles: %dx%d, tilesize=%d, overlap=%d)", w, h, out_w, out_h, xtiles, ytiles, tile_size, overlap);
    return true;
}
