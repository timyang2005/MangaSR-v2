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

    int widthOri = w;
    int heightOri = h;
    int padded_w = w;
    int padded_h = h;
    if (padded_w < tile_size) padded_w = tile_size;
    if (padded_h < tile_size) padded_h = tile_size;
    bool withPadding = (padded_w != widthOri || padded_h != heightOri);

    ncnn::Mat padded_in;
    if (withPadding) {
        padded_in = ncnn::Mat(padded_w, padded_h, 3);
        for (int c = 0; c < 3; c++) {
            for (int y = 0; y < padded_h; y++) {
                float* dst_row = static_cast<float*>(padded_in.channel(c).row(y));
                for (int x = 0; x < padded_w; x++) {
                    int px = std::min(x, widthOri - 1);
                    int py = std::min(y, heightOri - 1);
                    dst_row[x] = static_cast<const float*>(inimage.channel(c).row(py))[px];
                }
            }
        }
    } else {
        padded_in = inimage;
    }

    int numX = 1;
    for (; numX < 100 && (tile_size * numX - padded_w) / std::max(1, numX - 1) < 12; numX++);
    int numY = 1;
    for (; numY < 100 && (tile_size * numY - padded_h) / std::max(1, numY - 1) < 12; numY++);

    if (numX == 1 && numY == 1) {
        outimage.create(out_w, out_h, 3);
        if (outimage.empty()) {
            LOGE("Failed to create output Mat");
            return false;
        }

        ncnn::Mat out_tile;
        ncnn::Extractor ex = net.create_extractor();
        const char* input_blob = (modelType == "realcugan") ? "in0" : "data";
        const char* output_blob = (modelType == "realcugan") ? "out0" : "output";
        ex.input(input_blob, padded_in);
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
        LOGI("Process complete (single padded tile): %dx%d -> %dx%d", w, h, out_w, out_h);
        return true;
    }

    std::vector<int> locsX(numX);
    std::vector<int> locsY(numY);
    std::vector<int> padLeft(numX);
    std::vector<int> padTop(numY);
    std::vector<int> padRight(numX);
    std::vector<int> padBottom(numY);

    int totalLapX = tile_size * numX - padded_w;
    int totalLapY = tile_size * numY - padded_h;
    int baseLapX = totalLapX / std::max(1, numX - 1);
    int baseLapY = totalLapY / std::max(1, numY - 1);
    int extraLapX = totalLapX - baseLapX * (numX - 1);
    int extraLapY = totalLapY - baseLapY * (numY - 1);

    locsX[0] = 0;
    for (int i = 1; i < numX; i++) {
        locsX[i] = locsX[i - 1] + tile_size - baseLapX - (i <= extraLapX ? 1 : 0);
    }
    locsY[0] = 0;
    for (int i = 1; i < numY; i++) {
        locsY[i] = locsY[i - 1] + tile_size - baseLapY - (i <= extraLapY ? 1 : 0);
    }

    padLeft[0] = 0;
    padTop[0] = 0;
    padRight[numX - 1] = 0;
    padBottom[numY - 1] = 0;
    for (int i = 1; i < numX; i++) {
        padLeft[i] = (locsX[i - 1] + tile_size - locsX[i]) / 2;
    }
    for (int i = 1; i < numY; i++) {
        padTop[i] = (locsY[i - 1] + tile_size - locsY[i]) / 2;
    }
    for (int i = 0; i < numX - 1; i++) {
        padRight[i] = locsX[i] + tile_size - locsX[i + 1] - padLeft[i + 1];
    }
    for (int i = 0; i < numY - 1; i++) {
        padBottom[i] = locsY[i] + tile_size - locsY[i + 1] - padTop[i + 1];
    }

    int padded_out_w = padded_w * scale;
    int padded_out_h = padded_h * scale;
    int out_tile_w = tile_size * scale;
    int out_tile_h = tile_size * scale;

    ncnn::Mat padded_out(padded_out_w, padded_out_h, 3);
    if (padded_out.empty()) {
        LOGE("Failed to create padded output Mat");
        return false;
    }

    for (int c = 0; c < 3; c++) {
        float* ch_data = static_cast<float*>(padded_out.channel(c).data);
        memset(ch_data, 0, padded_out_w * padded_out_h * sizeof(float));
    }

    ncnn::Extractor ex = net.create_extractor();
    const char* input_blob = (modelType == "realcugan") ? "in0" : "data";
    const char* output_blob = (modelType == "realcugan") ? "out0" : "output";

    int numTiles = numX * numY;
    int currentTile = 0;

    for (int xi = 0; xi < numX; xi++) {
        for (int yi = 0; yi < numY; yi++) {
            if (!is_valid.load(std::memory_order_acquire)) {
                LOGE("Wrapper invalidated during tiling, aborting");
                return false;
            }

            int x1 = locsX[xi];
            int y1 = locsY[yi];

            ncnn::Mat in_tile(tile_size, tile_size, 3);
            for (int c = 0; c < 3; c++) {
                for (int y = 0; y < tile_size; y++) {
                    const float* src_row = static_cast<const float*>(padded_in.channel(c).row(y1 + y));
                    float* dst_row = static_cast<float*>(in_tile.channel(c).row(y));
                    memcpy(dst_row, src_row + x1, tile_size * sizeof(float));
                }
            }

            ncnn::Mat out_tile;
            ex.input(input_blob, in_tile);
            int ret = ex.extract(output_blob, out_tile);

            if (ret != 0 || out_tile.empty()) {
                LOGE("Inference failed for tile (%d,%d), ret=%d", xi, yi, ret);
                return false;
            }

            int out_x1 = (x1 + padLeft[xi]) * scale;
            int out_y1 = (y1 + padTop[yi]) * scale;
            int out_valid_w = (tile_size - padLeft[xi] - padRight[xi]) * scale;
            int out_valid_h = (tile_size - padTop[yi] - padBottom[yi]) * scale;

            for (int c = 0; c < 3; c++) {
                for (int sy = 0; sy < out_valid_h; sy++) {
                    int dst_y = out_y1 + sy;
                    if (dst_y >= padded_out_h) continue;
                    const float* src_row = static_cast<const float*>(out_tile.channel(c).row(padTop[yi] * scale + sy));
                    float* dst_row = static_cast<float*>(padded_out.channel(c).row(dst_y));

                    for (int sx = 0; sx < out_valid_w; sx++) {
                        int dst_x = out_x1 + sx;
                        if (dst_x >= padded_out_w) continue;
                        dst_row[dst_x] = src_row[padLeft[xi] * scale + sx];
                    }
                }
            }

            currentTile++;
            if (currentTile % 10 == 0 || currentTile == numTiles) {
                LOGI("Tiling progress: %d/%d tiles processed", currentTile, numTiles);
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

    LOGI("Process complete: %dx%d -> %dx%d (tiles: %dx%d, tilesize=%d, padded: %dx%d)",
         w, h, out_w, out_h, numX, numY, tile_size, padded_w, padded_h);
    return true;
}
