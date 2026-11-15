cmake_minimum_required(VERSION 3.22.1)
project(px4_jni CXX)

# libpx4.so from PX4's own build; the app packages it next to this library
add_library(px4 SHARED IMPORTED)
set_target_properties(px4 PROPERTIES IMPORTED_LOCATION ${PX4_LIBRARY})

add_library(px4_jni SHARED px4_jni.cpp)
target_link_libraries(px4_jni px4 log)
target_link_options(px4_jni PRIVATE "-Wl,-z,max-page-size=16384")
