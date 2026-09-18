# JNI methods are looked up by name from native code.
-keepclasseswithmembernames class * {
    native <methods>;
}
# Entry points host apps call and the service AGP instantiates by name.
-keep class com.esc.irminsul.CaptureService { *; }
-keep class com.esc.irminsul.NativeLib { *; }
