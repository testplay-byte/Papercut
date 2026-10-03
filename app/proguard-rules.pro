# Papercut ProGuard rules.
# kotlinx-serialization keeps generated serializers; Ktor/OkHttp ship their own rules.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.papercut.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.papercut.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Tink (via androidx security-crypto) references compile-only Error Prone
# annotations that never ship to Android — safe to ignore.
-dontwarn com.google.errorprone.annotations.**

# OkHttp's optional SLF4J service loader binding is absent on Android — safe.
-dontwarn org.slf4j.**

# Ktor/OkHttp optional platform integrations not present on Android.
-dontwarn javax.annotation.**
-dontwarn io.opentelemetry.**
