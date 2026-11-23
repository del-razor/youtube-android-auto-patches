# YouTube ships its own, heavily optimized copies of androidx and Kotlin classes under their
# original names. When the patcher merges the extension into YouTube, a class with the same
# name is merged with YouTube's copy, which breaks it (for example
# "InstantiationError: androidx.collection.SimpleArrayMap" when the car screen opens).
#
# So everything that is not ours and not the Android Auto SDK is renamed into a private
# package. R8 updates every reference, including those inside the SDK.

-repackageclasses 'app.delrazor.shaded'
-allowaccessmodification
-dontoptimize
-keepattributes *

# Our code: referenced by name from the patched manifest and patched YouTube code.
-keep class app.delrazor.** { *; }

# Android Auto SDK: Android Auto's host code calls into these by name.
-keep class com.google.android.apps.auto.sdk.** { *; }
-keep class com.google.android.gms.car.** { *; }
-keep class android.support.car.** { *; }

-dontwarn **
-ignorewarnings
