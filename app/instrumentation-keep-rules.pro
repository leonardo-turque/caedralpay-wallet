# Only the isolated instrumentation build loads a second APK referencing these
# shared APIs by original names. These rules are absent from distributable builds.
-keep class androidx.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
