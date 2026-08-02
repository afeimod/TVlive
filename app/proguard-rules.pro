# Add project specific ProGuard rules here.
-keep class com.tvlive.app.data.model.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
