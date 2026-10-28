cmake_minimum_required(VERSION 3.22.1)
project("poise_native")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# STFT/iSTFT and the GTCRN model
add_library(poise_native SHARED
    jni_bridge.cpp
    gtcrn.cpp
    stft.cpp
)
# The model runs every 16 ms, so debug builds need optimized code too (this comes after -O0)
target_compile_options(poise_native PRIVATE -O2)

# Link against Android libraries
find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(poise_native
    ${log-lib}
    ${android-lib}
)
