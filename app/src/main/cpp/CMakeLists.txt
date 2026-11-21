cmake_minimum_required(VERSION 3.22.1)
project(wxsilk C)

# Skype SILK v3 定点解码器 + LAME，跟着应用一起编译。
# 目的之一是把 .so 编成 16KB 页对齐，第三方预编译库只到 4KB，Android 16 上会弹兼容性警告。
file(GLOB SILK_SOURCES "${CMAKE_CURRENT_SOURCE_DIR}/libsilk/*.c")
file(GLOB LAME_SOURCES "${CMAKE_CURRENT_SOURCE_DIR}/libmp3lame/*.c")

add_library(wxsilk SHARED
        silk_jni.c
        silk_decoder.c
        ${SILK_SOURCES}
        ${LAME_SOURCES})

target_include_directories(wxsilk PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/include
        ${CMAKE_CURRENT_SOURCE_DIR}/libmp3lame)

# 老代码里有大量隐式转换告警，关掉以免刷屏
target_compile_options(wxsilk PRIVATE
        -O2
        -w
        -DSTDC_HEADERS
        -DHAVE_CONFIG_H
        -DFPM_DEFAULT)

target_link_options(wxsilk PRIVATE "-Wl,-z,max-page-size=16384")

find_library(log-lib log)
target_link_libraries(wxsilk ${log-lib} m)
