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

set(NCNN_BASE_DIR ${CMAKE_SOURCE_DIR}/ncnn/ncnn-20260112-android-vulkan)
set(NCNN_LIB_DIR ${NCNN_BASE_DIR}/${ANDROID_ABI}/lib)

target_include_directories(realesrgan-ncnn-vulkan PRIVATE ${NCNN_BASE_DIR}/include)

target_link_libraries(realesrgan-ncnn-vulkan
    ${NCNN_LIB_DIR}/libncnn.a
    android
    log
    jnigraphics
    vulkan
)
