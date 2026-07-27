# Consumer ProGuard rules for the rdsdk AAR.
# These are applied to the host app that consumes this library.

# Keep the public entry point.
-keep class com.carriez.flutter_hbb.RDMainRunner { *; }
-keep class com.carriez.flutter_hbb.RDMainActivity { *; }

# Keep everything in the controlled-end package: many methods/fields are
# referenced from the Rust core via JNI (see @Keep-annotated callbacks in
# ffi.kt / MainService) and by reflection through Flutter method channels.
-keep class com.carriez.flutter_hbb.** { *; }
-keep class ffi.** { *; }

# Anything annotated @Keep must survive shrinking.
-keep,allowobfuscation @interface androidx.annotation.Keep
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# Flutter embedding.
-keep class io.flutter.** { *; }
-keep class io.flutter.plugins.** { *; }
-dontwarn io.flutter.**

# Generated protobuf (message.proto -> hbb.MessageOuterClass).
-keep class hbb.** { *; }
