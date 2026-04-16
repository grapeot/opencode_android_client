# Add project specific ProGuard rules here.
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep generic signatures for Retrofit and Serialization
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses

# Kotlinx Serialization - keep @Serializable class names for reflection
-dontwarn kotlinx.serialization.**
-keep,includedescriptorclasses class com.yage.opencode_client.data.model.** { *; }
-keep,includedescriptorclasses class com.yage.opencode_client.data.api.** { *; }

# Retrofit
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes Signature
-keepattributes Exceptions

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile