#ifndef WXSILK_LAME_CONFIG_H
#define WXSILK_LAME_CONFIG_H

/* 移植到 Android 时不需要 autotools 的复杂探测，只补 4.0 源码直接引用的两个浮点类型。
   其余 HAVE_* 宏由 CMakeLists.txt 的编译参数提供。 */
typedef float ieee754_float32_t;
typedef double ieee754_float64_t;

#endif
