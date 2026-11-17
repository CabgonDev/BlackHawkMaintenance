############################################
# Common metadata used by Kotlin and annotations
############################################
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,RuntimeVisibleParameterAnnotations,RuntimeInvisibleParameterAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod
-keep class kotlin.Metadata { *; }

############################################
# Application components and Fragment restoration
############################################
# Components declared in AndroidManifest.xml must keep their entry point and name.
-keep,allowoptimization class com.cabgon.blackhawk.App { public <init>(); }
-keep,allowoptimization class com.cabgon.blackhawk.** extends android.app.Activity { public <init>(); }
-keep,allowoptimization class com.cabgon.blackhawk.** extends android.app.Service { public <init>(); }

# FragmentManager restores fragments by their class name after process death.
-keep,allowoptimization class com.cabgon.blackhawk.** extends androidx.fragment.app.Fragment { public <init>(); }

# AdminHomeFragment instantiates these modules through Class.forName().
-keep,allowoptimization class com.cabgon.blackhawk.ui.admin.users.UsersAdminFragment { public <init>(); }
-keep,allowoptimization class com.cabgon.blackhawk.admin.frequencies.AdminFrequenciesFragment { public <init>(); }
-keep,allowoptimization class com.cabgon.blackhawk.admin.generalities.AdminGeneralitiesDraftFragment { public <init>(); }
-keep,allowoptimization class com.cabgon.blackhawk.ui.admin.enruta.AdminEnRutaFragment { public <init>(); }
-keep,allowoptimization class com.cabgon.blackhawk.ui.admin.gallery.AdminGalleryFragment { public <init>(); }
-keep,allowoptimization class com.cabgon.blackhawk.admin.frequencies.AdminAuditLogFragment { public <init>(); }

############################################
# Room generated implementations
############################################
# Room resolves *_Impl classes reflectively at runtime.
-keep,allowoptimization class **_Impl { *; }

############################################
# Moshi codegen and Kotlin reflection fallback
############################################
# @JsonClass models and generated adapters are resolved by adapter name.
-keepnames @com.squareup.moshi.JsonClass class *
-keep @com.squareup.moshi.JsonClass class * { *; }
-keep class **JsonAdapter { public <init>(com.squareup.moshi.Moshi); }
-keep class com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory { *; }

############################################
# Retrofit
############################################
# Retrofit creates this interface dynamically from its annotations.
-keep,allowoptimization interface com.cabgon.blackhawk.data.PartApi { *; }
-keepclasseswithmembers,allowoptimization,allowshrinking class * {
    @retrofit2.http.* <methods>;
}

############################################
# ML Kit Translate
############################################
# ML Kit loads optional model providers by name in some versions.
-keep class com.google.mlkit.nl.translate.** { *; }
-keep class com.google.mlkit.common.model.RemoteModel { *; }

############################################
# Requery SQLite FTS and PDF viewer/PDFium
############################################
-keep class org.sqlite.** { *; }
-keep class com.github.barteksc.pdfviewer.** { *; }
-keep class com.shockwave.pdfium.** { *; }
-dontwarn com.gemalto.jp2.JP2Decoder
