cmake_minimum_required(VERSION 3.22.1)
project("poise_native")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# STFT/iSTFT, the GTCRN model and DeepFilterNet3
add_library(poise_native SHARED
    jni_bridge.cpp
    gtcrn.cpp
    df3.cpp
    stft.cpp
)
# The model runs every 16 ms, so debug builds need optimized code too (this comes after -O0)
target_compile_options(poise_native PRIVATE -O2)
# Loadable on phones with 16 KB pages (NDK r25 links for 4 KB)
target_link_options(poise_native PRIVATE -Wl,-z,max-page-size=16384)

# Link against Android libraries
find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(poise_native
    ${log-lib}
    ${android-lib}
)
