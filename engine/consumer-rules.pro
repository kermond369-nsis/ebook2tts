-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class com.tencent.mmkv.** { *; }
-keep class com.kermond.ebook2tts.engine.SherpaAudioCallback {
    *;
}
-keep class com.kermond.ebook2tts.engine.SherpaBackend {
    *;
}
-keep class com.kermond.ebook2tts.engine.SherpaBackend$* {
    public java.lang.Integer invoke(float[]);
    public java.lang.Object invoke(java.lang.Object);
}
-keep class * implements android.os.Parcelable { public static final ** CREATOR; }
-keepclasseswithmembernames class * { native <methods>; }
