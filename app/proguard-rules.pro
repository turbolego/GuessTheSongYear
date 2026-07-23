# Keep InnerTube models accessed via reflection
-keep class com.turbolego.songguesser.ApiVideo { *; }
-keep class com.turbolego.songguesser.KnownVideo { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
