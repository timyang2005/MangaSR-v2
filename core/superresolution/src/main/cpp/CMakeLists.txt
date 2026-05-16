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
    ${CMAKE_SOURCE_DIR}/ncnn/include/ncnn
)

set(NCNN_LIB_DIR ${CMAKE_SOURCE_DIR}/ncnn/lib/${ANDROID_ABI})

target_link_libraries(realesrgan-ncnn-vulkan
    ${NCNN_LIB_DIR}/libncnn.a
    ${NCNN_LIB_DIR}/libSPIRV.a
    ${NCNN_LIB_DIR}/libglslang.a
    ${NCNN_LIB_DIR}/libMachineIndependent.a
    ${NCNN_LIB_DIR}/libGenericCodeGen.a
    ${NCNN_LIB_DIR}/libglslang-default-resource-limits.a
    ${NCNN_LIB_DIR}/libOSDependent.a
    android
    log
    jnigraphics
    vulkan
)
