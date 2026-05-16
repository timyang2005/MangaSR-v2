cmake_minimum_required(VERSION 3.22.1)
project(mihon_superresolution)

set(CMAKE_CXX_STANDARD 17)

add_library(realesrgan-ncnn-vulkan SHARED
    realesrgan_jni.cpp
    realesrgan_wrapper.cpp
    manga_bw_postprocessor.cpp
)

target_include_directories(realesrgan-ncnn-vulkan PRIVATE
    ${CMAKE_SOURCE_DIR}/ncnn/include
)

find_library(ANDROID_LOG log)
find_library(ANDROID_JNIGRAPHICS jnigraphics)

target_link_libraries(realesrgan-ncnn-vulkan
    ncnn
    android
    log
    jnigraphics
    vulkan
)
