# GeckoView ships its own consumer rules inside the AAR.
# Keep line numbers so crash logs from the phone are readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# snakeyaml (a GeckoView dependency) references java.beans, which Android doesn't have.
# GeckoView only uses it for its debug config file, so these classes are never needed.
-dontwarn java.beans.**

# GeckoView's native code (libxul) looks up its Java classes and members through JNI at
# startup. Shrinking or renaming them crashes the engine (SIGSEGV in the launcher thread),
# so keep the engine's Java side exactly as shipped.
-keep class org.mozilla.** { *; }
-keep interface org.mozilla.** { *; }
-keep class org.webrtc.** { *; }
-dontwarn org.mozilla.**
