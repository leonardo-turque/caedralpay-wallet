# Preserve JNI entry points and native lifetime wrappers.
-keep class wallet.core.jni.** { *; }
-keep class wallet.core.java.AnySigner { *; }
