# ChrisAI release rules.
# Model DTOs are only used internally via org.json, no reflection needed.
# OkHttp ships its own consumer rules. Markwon does not require rules.

# Keep line numbers for stack traces in release but hide original source.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Avoid accidentally stripping Kotlin coroutines internals.
-dontwarn org.jetbrains.annotations.**

# JNI: the native `chriscore` module binds by exact names
# (Java_com_chrispixel_chrisai_nativebridge_NativeBridge_*), so the bridge and
# its `native` methods must keep their names, otherwise crypto/SSE/aurora break.
-keep class com.chrispixel.chrisai.nativebridge.NativeBridge { native <methods>; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

# Deeper aggressive bytecode optimization (safe: no reflection in this app).
-allowaccessmodification
-mergeinterfacesaggressively