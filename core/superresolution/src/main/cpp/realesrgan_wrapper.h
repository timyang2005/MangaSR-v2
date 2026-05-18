#pragma once

#include <string>
#include <ncnn/net.h>

class RealESRGANWrapper {
public:
    ncnn::Net net;
    int scale = 2;
    int tilesize = 0;
    int gpuid = -1;
    bool loaded = false;
    bool useFp16 = true;
    std::string modelType = "realesrgan";

    bool load(const char* param_path, const char* model_path, int gpu_id, const char* model_type, int initial_scale);
    bool process(const ncnn::Mat& inimage, ncnn::Mat& outimage);

private:
    int lastScale = 0;
    int calculateOptimalTileSize();
};
