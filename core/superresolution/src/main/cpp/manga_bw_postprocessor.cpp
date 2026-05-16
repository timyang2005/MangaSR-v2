#include "manga_bw_postprocessor.h"
#include <algorithm>
#include <cstring>
#include <vector>
#include <cmath>

static inline unsigned char toGray(unsigned char r, unsigned char g, unsigned char b) {
    return static_cast<unsigned char>(0.299f * r + 0.587f * g + 0.114f * b);
}

void quantizeGrayscale(unsigned char* rgb_data, int width, int height, int grayLevels) {
    if (grayLevels < 2) return;

    int pixel_count = width * height;
    int step = 255 / (grayLevels - 1);

    for (int i = 0; i < pixel_count; i++) {
        unsigned char gray = toGray(rgb_data[i * 3 + 0], rgb_data[i * 3 + 1], rgb_data[i * 3 + 2]);
        int level = (gray + step / 2) / step;
        level = std::max(0, std::min(grayLevels - 1, level));
        unsigned char quantized = static_cast<unsigned char>(level * step);
        rgb_data[i * 3 + 0] = quantized;
        rgb_data[i * 3 + 1] = quantized;
        rgb_data[i * 3 + 2] = quantized;
    }
}

static int computeOtsuThreshold(unsigned char* rgb_data, int width, int height) {
    int histogram[256] = {0};
    int pixel_count = width * height;

    for (int i = 0; i < pixel_count; i++) {
        unsigned char gray = toGray(rgb_data[i * 3 + 0], rgb_data[i * 3 + 1], rgb_data[i * 3 + 2]);
        histogram[gray]++;
    }

    float total = static_cast<float>(pixel_count);
    float sum = 0.0f;
    for (int i = 0; i < 256; i++) {
        sum += i * histogram[i];
    }

    float sum_b = 0.0f;
    int w_b = 0;
    float max_variance = 0.0f;
    int threshold = 0;

    for (int t = 0; t < 256; t++) {
        w_b += histogram[t];
        if (w_b == 0) continue;

        int w_f = pixel_count - w_b;
        if (w_f == 0) break;

        sum_b += t * histogram[t];

        float m_b = sum_b / w_b;
        float m_f = (sum - sum_b) / w_f;

        float variance = static_cast<float>(w_b) * static_cast<float>(w_f) * (m_b - m_f) * (m_b - m_f);

        if (variance > max_variance) {
            max_variance = variance;
            threshold = t;
        }
    }

    return threshold;
}

void binarizeEnhance(unsigned char* rgb_data, int width, int height, int threshold) {
    int pixel_count = width * height;
    int thresh = threshold;

    if (thresh <= 0) {
        thresh = computeOtsuThreshold(rgb_data, width, height);
    }

    for (int i = 0; i < pixel_count; i++) {
        unsigned char gray = toGray(rgb_data[i * 3 + 0], rgb_data[i * 3 + 1], rgb_data[i * 3 + 2]);
        unsigned char val = (gray > thresh) ? 255 : 0;
        rgb_data[i * 3 + 0] = val;
        rgb_data[i * 3 + 1] = val;
        rgb_data[i * 3 + 2] = val;
    }
}

static void morphErode(unsigned char* data, int width, int height, int radius) {
    std::vector<unsigned char> temp(width * height);
    memcpy(temp.data(), data, width * height);

    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            unsigned char min_val = 255;
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    int ny = std::max(0, std::min(height - 1, y + dy));
                    int nx = std::max(0, std::min(width - 1, x + dx));
                    min_val = std::min(min_val, temp[ny * width + nx]);
                }
            }
            data[y * width + x] = min_val;
        }
    }
}

static void morphDilate(unsigned char* data, int width, int height, int radius) {
    std::vector<unsigned char> temp(width * height);
    memcpy(temp.data(), data, width * height);

    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            unsigned char max_val = 0;
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    int ny = std::max(0, std::min(height - 1, y + dy));
                    int nx = std::max(0, std::min(width - 1, x + dx));
                    max_val = std::max(max_val, temp[ny * width + nx]);
                }
            }
            data[y * width + x] = max_val;
        }
    }
}

void densityCorrection(unsigned char* rgb_data, int width, int height, bool enable) {
    if (!enable) return;

    int pixel_count = width * height;
    std::vector<unsigned char> gray(pixel_count);

    for (int i = 0; i < pixel_count; i++) {
        gray[i] = toGray(rgb_data[i * 3 + 0], rgb_data[i * 3 + 1], rgb_data[i * 3 + 2]);
    }

    morphErode(gray.data(), width, height, 1);
    morphDilate(gray.data(), width, height, 1);

    for (int i = 0; i < pixel_count; i++) {
        rgb_data[i * 3 + 0] = gray[i];
        rgb_data[i * 3 + 1] = gray[i];
        rgb_data[i * 3 + 2] = gray[i];
    }
}

void processMangaBW(unsigned char* rgb_data, int width, int height, int grayLevels, bool enableDensityCorrection) {
    quantizeGrayscale(rgb_data, width, height, grayLevels);

    if (grayLevels <= 2) {
        binarizeEnhance(rgb_data, width, height, 0);
    }

    densityCorrection(rgb_data, width, height, enableDensityCorrection);
}
