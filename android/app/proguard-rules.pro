# Minification is disabled for v1 (isMinifyEnabled = false). Rules below are kept so the
# project shrinks cleanly if a developer flips the flag later.

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.carradio.app.**$$serializer { *; }
-keepclassmembers class com.carradio.app.** { *** Companion; }
-keepclasseswithmembers class com.carradio.app.** { kotlinx.serialization.KSerializer serializer(...); }

# Ktor / OkHttp
-dontwarn org.slf4j.**
-dontwarn okhttp3.**
-dontwarn io.ktor.**
