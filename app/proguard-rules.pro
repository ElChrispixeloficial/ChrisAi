# ChrisAI release rules.
# Model DTOs are only used internally via org.json, no reflection needed.
# OkHttp ships its own consumer rules. Markwon does not require rules.

# Keep line numbers for stack traces in release but hide original source.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Avoid accidentally stripping Kotlin coroutines internals.
-dontwarn org.jetbrains.annotations.**