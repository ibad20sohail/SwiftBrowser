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

# 2.7, the Files screen's archives. Commons Compress refers to Brotli and Zstandard decoders
# Swift doesn't ship (it never reads those formats); junrar's logger (slf4j) looks for a logging
# backend that isn't there and then logs nothing.
-dontwarn org.brotli.dec.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.slf4j.impl.**

# 2.8, old Word files (Apache POI, see OldOffice). java.awt, which Android doesn't have, is only
# used by POI's debugging dump of drawing records (what their toString() prints) and by two
# getters of metafile pictures. Swift calls neither: it reads the records' fields. POI needs no
# keep rules on this path (it creates its records with constructors, not by reflection).
-dontwarn java.awt.**

# 2.9.2, torrents (libtorrent4j). Its native library calls back into the generated SWIG classes
# (and looks them up by name), so they keep their names and members.
-keep class org.libtorrent4j.swig.** { *; }
-dontwarn org.libtorrent4j.**

# 3.0.4, network folders (smbj, smbj-rpc). smbj's event bus (mbassador) finds the methods that take
# its events by their @Handler mark and makes their calls by reflection: without them, a share or
# connection that closed stays in smbj's tables and is handed out again, closed. So mbassador stays
# whole (it is small) and so do those methods.
-keep class net.engio.mbassy.** { *; }
-keepclassmembers class com.hierynomus.** {
    @net.engio.mbassy.listener.Handler <methods>;
}
# Kerberos sign-in (smbj leaves it out on Android), mbassador's EL filters (unused) and the
# java.rmi exceptions smbj-rpc throws only for an answer it can't read aren't on Android.
-dontwarn org.ietf.jgss.**
-dontwarn javax.el.**
-dontwarn java.rmi.**
