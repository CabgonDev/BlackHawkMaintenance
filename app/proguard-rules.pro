#############################
# Atributos útiles
#############################
-keepattributes *Annotation*, Signature, EnclosingMethod, InnerClasses

#############################
# Kotlin / Coroutines
#############################
-keep class kotlin.Metadata { *; }
-dontwarn kotlinx.coroutines.**
-dontwarn kotlin.**
-dontwarn kotlin.jvm.**
-dontwarn kotlin.reflect.**

-keep class com.cabgon.blackhawk.ui.fragments.PreflightNewDialog { *; }
-dontwarn kotlin.reflect.jvm.internal.**


#############################
# ML Kit Translate (solo lo necesario)
# En general ML Kit no requiere rules; limitamos a translate.
#############################
-dontwarn com.google.mlkit.**
-keep class com.google.mlkit.nl.translate.** { *; }
-keep class com.google.mlkit.common.model.RemoteModel { *; }

#############################
# SQLite (requery) con FTS5
#############################
-keep class org.sqlite.** { *; }
-dontwarn org.sqlite.**

#############################
# Android PDF Viewer (mhiew/barteksc) + PDFium
#############################
-keep class com.github.barteksc.pdfviewer.** { *; }
-keep class com.shockwave.pdfium.** { *; }
-dontwarn com.github.barteksc.pdfviewer.**
-dontwarn com.shockwave.pdfium.**

#############################
# Retrofit / OkHttp / Moshi (afinado)
#############################
# Conserva únicamente interfaces/funciones anotadas de Retrofit
-keepattributes RuntimeVisibleAnnotations, RuntimeInvisibleAnnotations
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# Retrofit core (tipos públicos) — sin abarcar todo el paquete
-keep class retrofit2.Retrofit { *; }
-keep class retrofit2.converter.moshi.MoshiConverterFactory { *; }

# OkHttp: normalmente no requiere keep; solo silenciamos warnings si aparecen
-dontwarn okhttp3.**
-dontwarn okio.**

# Moshi: conserva adapters y evita warnings
-keep class com.squareup.moshi.** { *; }
-dontwarn com.squareup.moshi.**
-keepclasseswithmembers class * {
    @com.squareup.moshi.* <methods>;
}
# Si usas KotlinJsonAdapterFactory:
-keep class com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory { *; }

#############################
# Material / AndroidX (ruidos comunes)
#############################
-dontwarn androidx.**
-dontwarn com.google.android.material.**

#############################
# Tu código (opcional; si quieres, recorta por paquetes internos)
#############################
-keep class com.cabgon.blackhawk.** { *; }
