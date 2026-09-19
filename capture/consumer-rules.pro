# JNI methods are looked up by name from native code.
-keepclasseswithmembernames class * {
    native <methods>;
}
# libcapture resolves onPacketCaptured / onCaptureStats / protectSocket by name
# on the service object, and libirminsul finds NativeLib through find_class.
# Keeping the package (not individual classes) means a later move *within* it
# cannot leave a stale rule behind.
-keep class com.esc.irminsul.capture.internal.** { *; }
