# R8 for the sideloaded release (APK under the 30 MiB file card limit).

# JNA finds Java classes, fields and callbacks by reflection and from native code.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**

# The UniFFI bindings: structures, callbacks and the library interface go through JNA.
-keep class uniffi.** { *; }

# The Oboe bridge is called from C++ by name.
-keep class com.fiftyhoursaweek.slowpage.NativeAudio { *; }
