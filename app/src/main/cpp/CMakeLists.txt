cmake_minimum_required(VERSION 3.22.1)
project("poise_native")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Find ONNX Runtime
# The AAR package puts the .so files in jniLibs, headers are extracted separately
set(ONNXRUNTIME_INCLUDE_DIR ${CMAKE_SOURCE_DIR}/include)

# Create the native library
add_library(poise_native SHARED
    jni_bridge.cpp
    poise_processor.cpp
    vad.cpp
    resampler.cpp
    stft.cpp
)

# Include ONNX Runtime headers
target_include_directories(poise_native PRIVATE
    ${ONNXRUNTIME_INCLUDE_DIR}
)

# Link against Android libraries
find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(poise_native
    ${log-lib}
    ${android-lib}
)

# Note: ONNX Runtime linking is handled via Java/Kotlin side
# The native code calls ONNX Runtime through JNI callbacks
