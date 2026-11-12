# Preserve JNI entry points and native lifetime wrappers.
-keep class wallet.core.jni.** { *; }
-keep class wallet.core.java.AnySigner { *; }

# Preserve package visibility boundaries across Kotlin/AndroidX inheritance.
# AGP repackaging caused IllegalAccessError at ProcessLifecycleInitializer startup.
-dontrepackage
