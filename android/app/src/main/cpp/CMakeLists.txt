cmake_minimum_required(VERSION 3.22)
project(motoparty_opus C)

# libopus from tools/fetch_opus.sh, built static and linked into one small JNI library.
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_DRED OFF CACHE BOOL "" FORCE)
set(OPUS_OSCE OFF CACHE BOOL "" FORCE)
add_subdirectory(opus EXCLUDE_FROM_ALL)

add_library(motoparty_opus SHARED opus_jni.c)
target_link_libraries(motoparty_opus PRIVATE opus log)
# 16 KB page alignment, required for apps on Android 15+ devices.
target_link_options(motoparty_opus PRIVATE "-Wl,-z,max-page-size=16384")
