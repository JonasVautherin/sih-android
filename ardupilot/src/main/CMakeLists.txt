cmake_minimum_required(VERSION 3.22.1)
project(sitl_jni C)

add_library(sitl_jni SHARED sitl_jni.c)
target_link_libraries(sitl_jni log dl)
target_link_options(sitl_jni PRIVATE "-Wl,-z,max-page-size=16384")
