# R8 rules for the release build (H5). Libraries that ship their own consumer rules (Compose,
# Media3, OkHttp 5, Okio, kotlinx.serialization, kotlinx.coroutines, Coil) need nothing here.

# Names stay as written. Motoparty is one phone's private build: nothing is gained by renaming,
# and the logs are read as they are — the in-app log and logcat print exception and class names,
# and tools/bench parses logcat by tag and message. Shrinking and optimisation stay on.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# --- JNI: libmotoparty_opus.so binds to these by name (src/main/cpp/opus_jni.c) ---
-keep class com.kivan.motoparty.audio.Opus {
    native <methods>;
}

# --- kotlinx.serialization: the wire messages, settings and history ---
# The library's own rules keep the generated serializers of @Serializable classes; this keeps the
# Companion.serializer() lookups of our classes unconditionally (they are reached through
# `serializer<T>()` and polymorphic lookups, which R8 cannot always see through).
-keepclassmembers @kotlinx.serialization.Serializable class com.kivan.motoparty.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.kivan.motoparty.**$$serializer { *; }

# --- NewPipeExtractor (the rules of the NewPipe app itself) ---
# "time ago" parsing loads its language patterns by class name.
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
# Services and their link handlers are looked up through ServiceList at run time.
-keep class org.schabi.newpipe.extractor.services.** { *; }
# Rhino runs YouTube's player JavaScript (signature / throttling functions) and is reflective
# throughout: ScriptableObject finds jsFunction_/jsGet_ methods by name, and the interpreter
# loads classes by string.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
# Desktop-JVM APIs that Rhino refers to and Android does not have: its JSR-223 script engine
# (javax.script), bean introspection (java.beans) and the compiled mode's linker (jdk.dynalink).
# The extractor runs Rhino interpreted and reaches none of them — the debug build has been
# running without these classes all along. Exactly the three packages R8 reports; nothing else
# is silenced, so a new missing class fails the build.
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
# protobuf-lite messages (YouTube's player requests) are read and written through reflection
# on their fields.
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
