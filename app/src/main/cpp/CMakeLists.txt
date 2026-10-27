cmake_minimum_required(VERSION 3.22.1)
project("poise_native")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Native STFT/iSTFT for GTCRN; ONNX Runtime is used from Kotlin
add_library(poise_native SHARED
    jni_bridge.cpp
    stft.cpp
)

# Link against Android libraries
find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(poise_native
    ${log-lib}
    ${android-lib}
)
