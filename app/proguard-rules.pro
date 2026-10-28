# Poise Android ProGuard Rules

# Keep JNI methods
-keepclasseswithmembers class * {
    native <methods>;
}

# Keep Kotlin coroutines
-keepclassmembers class kotlinx.coroutines.** { *; }
