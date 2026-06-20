# Add project-specific ProGuard rules here.

# kotlinx.serialization
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * { @kotlinx.serialization.Serializable <fields>; }
-keep,includedescriptorclasses class com.blizzardcaron.freeolleefaces.**$$serializer { *; }
-keepclassmembers class com.blizzardcaron.freeolleefaces.** {
    *** Companion;
}
-keepclasseswithmembers class com.blizzardcaron.freeolleefaces.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# ktor (OkHttp engine)
-keep class io.ktor.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn org.slf4j.**
# ktor's IntellijIdeaDebugDetector references JVM-only java.lang.management
# classes that don't exist on Android; never invoked at runtime here.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
