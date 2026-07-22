# Keep YouTube player
-keep class com.pierfrancescosoffritti.androidyoutubeplayer.** { *; }

# Keep InnerTube models accessed via reflection
-keep class com.turbolego.songguesser.ApiVideo { *; }
-keep class com.turbolego.songguesser.KnownVideo { *; }

# OkHttp is used via reflection in some cases
-dontwarn okhttp3.**
-dontwarn okio.**

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
