cmake_minimum_required(VERSION 3.22.1)
project(wxsilk C)

# Skype SILK v3 定点解码器 + LAME，跟着应用一起编译。
# 目的之一是把 .so 编成 16KB 页对齐，第三方预编译库只到 4KB，Android 16 上会弹兼容性警告。
file(GLOB SILK_SOURCES "${CMAKE_CURRENT_SOURCE_DIR}/libsilk/*.c")
# LAME 源码显式列出，避免上游新增文件被 GLOB 意外纳入编译
set(LAME_SOURCES
        "${CMAKE_CURRENT_SOURCE_DIR}/libmp3lame/VbrTag.c"
        "${CMAKE_CURRENT_SOURCE_DIR}/libmp3lame/bitstream.c"
        "${CMAKE_CURRENT_SOURCE_DIR}/libmp3lame/encoder.c"
        "${CMAKE_CURRENT_SOURCE_DIR}/libmp3lame/fft.c"
        "${CMAKE_CURRENT_SOURCE_DIR}/libmp3lame/gain_analysis.c"
        "${CMAKE_CURRENT_SOURCE_DIR}/libmp3lame/id3tag.c"
        "${CMAKE_CURRENT_SOURCE_DIR}/libmp3lame/lame.c"
        "${CMAKE_CURRENT_SOURCE_DIR}/libmp3lame/newmdct.c"
        "${CMAKE_CURRENT_SOURCE_DIR}/libmp3lame/presets.c"
        "${CMAKE_CURRENT_SOURCE_DIR}/libmp3lame/psymodel.c"
        "${CMAKE_CURRENT_SOURCE_DIR}/libmp3lame/quantize.c"
        "${CMAKE_CURRENT_SOURCE_DIR}/libmp3lame/quantize_pvt.c"
        "${CMAKE_CURRENT_SOURCE_DIR}/libmp3lame/reservoir.c"
        "${CMAKE_CURRENT_SOURCE_DIR}/libmp3lame/set_get.c"
        "${CMAKE_CURRENT_SOURCE_DIR}/libmp3lame/tables.c"
        "${CMAKE_CURRENT_SOURCE_DIR}/libmp3lame/takehiro.c"
        "${CMAKE_CURRENT_SOURCE_DIR}/libmp3lame/util.c"
        "${CMAKE_CURRENT_SOURCE_DIR}/libmp3lame/vbrquantize.c"
        "${CMAKE_CURRENT_SOURCE_DIR}/libmp3lame/version.c")

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
        -DHAVE_STDINT_H
        -DFPM_DEFAULT)

target_link_options(wxsilk PRIVATE "-Wl,-z,max-page-size=16384")

find_library(log-lib log)
target_link_libraries(wxsilk ${log-lib} m)
