cmake_minimum_required(VERSION 3.22.1)
project(mihon_superresolution)

set(CMAKE_CXX_STANDARD 17)

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fopenmp")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fopenmp")

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

set(NCNN_BASE_DIR ${CMAKE_SOURCE_DIR}/ncnn)
set(NCNN_LIB_DIR ${NCNN_BASE_DIR}/${ANDROID_ABI}/lib)

target_link_libraries(realesrgan-ncnn-vulkan
    ${NCNN_LIB_DIR}/libncnn.a
    ${NCNN_LIB_DIR}/libglslang.a
    ${NCNN_LIB_DIR}/libOSDependent.a
    ${NCNN_LIB_DIR}/libSPIRV.a
    ${NCNN_LIB_DIR}/libGenericCodeGen.a
    ${NCNN_LIB_DIR}/libMachineIndependent.a
    ${NCNN_LIB_DIR}/libglslang-default-resource-limits.a
    android
    log
    jnigraphics
    vulkan
    omp
)
