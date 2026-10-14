# The Anthropic SDK and Jackson use reflection and annotations for (de)serialization: keep them whole.
-keep class com.anthropic.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class kotlin.Metadata { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions

# Optional dependencies of the SDK's JSON-schema helpers and OkHttp that aren't on Android.
-dontwarn com.github.victools.**
-dontwarn io.swagger.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn java.lang.invoke.**
-dontwarn java.beans.**
-dontwarn java.lang.reflect.AnnotatedType

# ML Kit discovers its components reflectively (ComponentRegistrar implementations named in the manifest) and the
# bundled barcode model lives in gms.internal packages. Shrinking them produced an NPE in BarcodeScanning.getClient.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class com.google.android.gms.common.** { *; }
-keep class com.google.firebase.components.** { *; }
-keep class * implements com.google.firebase.components.ComponentRegistrar { *; }
-dontwarn com.google.android.gms.**
