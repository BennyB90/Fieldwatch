cmake_minimum_required(VERSION 3.22.1)
project(sweep_uvc LANGUAGES C)

add_library(sweep_uvc SHARED sweep_uvc_jni.c)
set_target_properties(sweep_uvc PROPERTIES C_STANDARD 11 C_STANDARD_REQUIRED YES)
target_compile_options(sweep_uvc PRIVATE -Wall -Wextra -Werror)
target_link_options(sweep_uvc PRIVATE "-Wl,-z,max-page-size=16384")
