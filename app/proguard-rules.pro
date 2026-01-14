# Poise Android ProGuard Rules

# Keep ONNX Runtime classes
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { *; }

# Keep JNI methods
-keepclasseswithmembers class * {
    native <methods>;
}

# Keep Kotlin coroutines
-keepclassmembers class kotlinx.coroutines.** { *; }
