# Release builds are shrunk with R8. Libraries that load classes by name keep them whole,
# so the map, Health Connect and background reminders behave exactly like the debug build.

-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, SourceFile, LineNumberTable

# Maps (MapLibre uses JNI and reflection)
-keep class org.maplibre.** { *; }
-keep interface org.maplibre.** { *; }
-dontwarn org.maplibre.**

# Health Connect
-keep class androidx.health.connect.client.** { *; }
-dontwarn androidx.health.connect.client.**

# Background reminders (WorkManager instantiates workers by class name)
-keep class * extends androidx.work.ListenableWorker { <init>(...); }

# Meridian's own enums are read back from saved files with valueOf(name)
-keepclassmembers enum com.meridian.tracker.** { *; }
-keepnames class com.meridian.tracker.**

-dontwarn com.google.android.gms.**
