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
