# Keep the OEM automotive SDK intact. It uses binder/parcelable style APIs and
# callback entry points where shrinking/obfuscation is more likely to break the
# integration than help it.
-keep class com.incall.** { *; }
-keep interface com.incall.** { *; }
-keep enum com.incall.** { *; }
-dontwarn com.incall.**

# android.car is provided by the head unit/framework, not bundled in the APK.
# We compile against the OEM jar, but at runtime the classes come from the
# system image, so suppress minifier noise around those references.
-dontwarn android.car.**
-dontwarn android.car.hardware.**

# Keep our automotive bridge layer readable and stable for the OEM SDK.
-keep class com.alexander.carplay.data.automotive.** { *; }

# Keep climate bridge and reflection-based car API integration stable.
-keep class com.alexander.carplay.presentation.climate.** { *; }

# Keep Android components and JNI/manifest entry points stable in release.
-keep class com.alexander.carplay.platform.service.** { *; }
-keep class com.alexander.carplay.presentation.ui.UsbAttachProxyActivity { *; }
-keep class com.alexander.carplay.platform.service.ServiceStartupReceiver { *; }
