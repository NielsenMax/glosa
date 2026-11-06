# ML Kit GenAI resolves its components by reflection; R8 strips them otherwise and
# Generation.getClient() fails with a NullPointerException.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_genai_** { *; }
-keep class com.google.android.gms.internal.mlkit_common.** { *; }
