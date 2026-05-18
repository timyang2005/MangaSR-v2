#include "realesrgan_wrapper.h"
#include <android/log.h>
#include <algorithm>
#include <cstring>
#include <vector>

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

        blob_vkallocator = new ncnn::VkBlobAllocator(net.vulkan_device());
        staging_vkallocator = new ncnn::VkStagingAllocator(net.vulkan_device());
        net.opt.blob_vkallocator = blob_vkallocator;
        net.opt.workspace_vkallocator = blob_vkallocator;
        net.opt.staging_vkallocator = staging_vkallocator;
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

    loaded = true;
    LOGI("Model loaded: type=%s, param=%s, model=%s, gpuid=%d, fp16=%d", modelType.c_str(), param_path, model_path, gpuid, useFp16);
    return true;
}

static inline float blendWeight(int pos, int fade_size) {
    if (fade_size <= 0) return 1.0f;
    if (pos < 0) return 0.0f;
    if (pos >= fade_size) return 1.0f;
    return static_cast<float>(pos) / static_cast<float>(fade_size);
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

    for (int c = 0; c < 3; c++) {
        float* ch_data = static_cast<float*>(outimage.channel(c).data);
        memset(ch_data, 0, out_w * out_h * sizeof(float));
    }

    std::vector<float> weight_sum(out_w * out_h, 0.0f);

    int tile_size = tilesize > 0 ? tilesize : 200;
    int min_overlap = 12;

    if (w <= tile_size && h <= tile_size) {
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

    int numX = 2;
    int maxTiles = (w / std::max(1, tile_size / 2)) + 2;
    while (numX <= maxTiles) {
        int overlap = (tile_size * numX - w) / (numX - 1);
        if (overlap >= min_overlap) break;
        numX++;
    }
    if (numX > maxTiles) numX = maxTiles;

    int numY = 2;
    int maxTilesY = (h / std::max(1, tile_size / 2)) + 2;
    while (numY <= maxTilesY) {
        int overlap = (tile_size * numY - h) / (numY - 1);
        if (overlap >= min_overlap) break;
        numY++;
    }
    if (numY > maxTilesY) numY = maxTilesY;

    int totalLapX = tile_size * numX - w;
    int totalLapY = tile_size * numY - h;
    int baseLapX = totalLapX / (numX - 1);
    int baseLapY = totalLapY / (numY - 1);
    int extraLapX = totalLapX - baseLapX * (numX - 1);
    int extraLapY = totalLapY - baseLapY * (numY - 1);

    std::vector<int> locsX(numX), locsY(numY);
    std::vector<int> padLeft(numX), padTop(numY), padRight(numX), padBottom(numY);

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

    bool use_gpu = (gpuid >= 0);
    ncnn::VkCompute* cmd = nullptr;
    if (use_gpu) {
        cmd = new ncnn::VkCompute(net.vulkan_device());
    }

    for (int yi = 0; yi < numY; yi++) {
        for (int xi = 0; xi < numX; xi++) {
            int x1 = locsX[xi];
            int y1 = locsY[yi];
            int x2 = std::min(x1 + tile_size, w);
            int y2 = std::min(y1 + tile_size, h);
            int tw = x2 - x1;
            int th = y2 - y1;

            ncnn::Mat in_tile(tw, th, 3);
            for (int c = 0; c < 3; c++) {
                for (int y = 0; y < th; y++) {
                    const float* src_row = static_cast<const float*>(inimage.channel(c).row(y1 + y));
                    float* dst_row = static_cast<float*>(in_tile.channel(c).row(y));
                    memcpy(dst_row, src_row + x1, tw * sizeof(float));
                }
            }

            ncnn::Mat out_tile;

            if (use_gpu && cmd) {
                ncnn::VkMat in_tile_gpu;
                cmd->record_clone(in_tile, in_tile_gpu);

                ncnn::VkMat out_tile_gpu;
                ncnn::Extractor ex = net.create_extractor();
                const char* input_blob = (modelType == "realcugan") ? "in0" : "data";
                const char* output_blob = (modelType == "realcugan") ? "out0" : "output";
                ex.input(input_blob, in_tile_gpu);
                ex.extract(output_blob, out_tile_gpu, *cmd);

                cmd->record_clone(out_tile_gpu, out_tile);
                cmd->submit_and_wait();
            } else {
                ncnn::Extractor ex = net.create_extractor();
                const char* input_blob = (modelType == "realcugan") ? "in0" : "data";
                const char* output_blob = (modelType == "realcugan") ? "out0" : "output";
                ex.input(input_blob, in_tile);
                ex.extract(output_blob, out_tile);
            }

            if (out_tile.empty()) {
                LOGE("Inference failed for tile (%d,%d)", xi, yi);
                if (cmd) delete cmd;
                return false;
            }

            int crop_left = padLeft[xi] * scale;
            int crop_top = padTop[yi] * scale;
            int crop_right = padRight[xi] * scale;
            int crop_bottom = padBottom[yi] * scale;

            int out_tile_w = out_tile.w;
            int out_tile_h = out_tile.h;

            int src_start_x = crop_left;
            int src_start_y = crop_top;
            int src_end_x = out_tile_w - crop_right;
            int src_end_y = out_tile_h - crop_bottom;

            int dst_start_x = (x1 + padLeft[xi]) * scale;
            int dst_start_y = (y1 + padTop[yi]) * scale;

            int fade_x = padLeft[xi] * scale;
            int fade_y = padTop[yi] * scale;
            int fade_rx = padRight[xi] * scale;
            int fade_by = padBottom[yi] * scale;

            for (int c = 0; c < 3; c++) {
                for (int sy = src_start_y; sy < src_end_y; sy++) {
                    int dy = dst_start_y + (sy - src_start_y);
                    if (dy >= out_h) break;

                    const float* src_row = static_cast<const float*>(out_tile.channel(c).row(sy));
                    float* dst_row = static_cast<float*>(outimage.channel(c).row(dy));

                    for (int sx = src_start_x; sx < src_end_x; sx++) {
                        int dx = dst_start_x + (sx - src_start_x);
                        if (dx >= out_w) break;

                        int local_x = sx - src_start_x;
                        int local_y = sy - src_start_y;
                        int region_w = src_end_x - src_start_x;
                        int region_h = src_end_y - src_start_y;

                        float w_x = 1.0f;
                        if (fade_x > 0 && local_x < fade_x) {
                            w_x = blendWeight(local_x, fade_x);
                        } else if (fade_rx > 0 && local_x >= region_w - fade_rx) {
                            w_x = blendWeight(region_w - 1 - local_x, fade_rx);
                        }

                        float w_y = 1.0f;
                        if (fade_y > 0 && local_y < fade_y) {
                            w_y = blendWeight(local_y, fade_y);
                        } else if (fade_by > 0 && local_y >= region_h - fade_by) {
                            w_y = blendWeight(region_h - 1 - local_y, fade_by);
                        }

                        float wt = w_x * w_y;
                        int out_idx = dy * out_w + dx;

                        dst_row[dx] += src_row[sx] * wt;
                        if (c == 0) {
                            weight_sum[out_idx] += wt;
                        }
                    }
                }
            }
        }
    }

    for (int c = 0; c < 3; c++) {
        for (int y = 0; y < out_h; y++) {
            float* dst_row = static_cast<float*>(outimage.channel(c).row(y));
            for (int x = 0; x < out_w; x++) {
                float ws = weight_sum[y * out_w + x];
                if (ws > 0.0f) {
                    dst_row[x] /= ws;
                }
            }
        }
    }

    if (cmd) delete cmd;

    LOGI("Process complete: %dx%d -> %dx%d (tiles: %dx%d)", w, h, out_w, out_h, numX, numY);
    return true;
}
