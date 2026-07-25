# Strip all Log.* calls from release builds (prevents logcat data leaks)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

# Keep InnerTube models accessed via reflection
-keep class com.turbolego.songguesser.ApiVideo { *; }
-keep class com.turbolego.songguesser.KnownVideo { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Android YouTube Player (IFrame API WebView — used in fragment_video_player.xml)
-keep class com.pierfrancescosoffritti.androidyoutubeplayer.** { *; }
-dontwarn com.pierfrancescosoffritti.androidyoutubeplayer.**
