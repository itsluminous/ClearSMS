# R8 rules for Clear SMS.
#
# Room, Hilt, WorkManager, Compose, DataStore and Coil all ship consumer
# proguard rules inside their artifacts, so they need nothing here. The rules
# below cover the one reflection-adjacent area that is app-specific:
# kotlinx.serialization of this app's own @Serializable models (rule JSON,
# backup documents, extracted-data payloads).

# --- kotlinx.serialization (per the library's official R8 guidance) --------
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# Keep generated serializer classes for the app's @Serializable models.
-keep,includedescriptorclasses class app.clearsms.**$$serializer { *; }
# serializer() lookups go through the Companion object.
-keepclassmembers class app.clearsms.** {
    *** Companion;
}
-keepclasseswithmembers class app.clearsms.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Diagnostics ------------------------------------------------------------
# Keep source file + line numbers so stack traces from users stay readable
# (the app is open source; obfuscation is not a goal, size is).
-keepattributes SourceFile,LineNumberTable
-dontobfuscate
