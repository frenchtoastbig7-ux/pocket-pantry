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
