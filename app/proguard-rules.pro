# Debug builds are unminified. Keep MediaCodec / camera entry points if minify is enabled later.
-keep class com.dualcampip.app.engine.** { *; }
-keep class com.dualcampip.app.encode.** { *; }
