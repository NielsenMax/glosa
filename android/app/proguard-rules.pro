# ML Kit GenAI resolves its components by reflection; R8 strips them otherwise and
# Generation.getClient() fails with a NullPointerException.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_genai_** { *; }
-keep class com.google.android.gms.internal.mlkit_common.** { *; }

# PDFium's native library builds Java objects such as com.shockwave.pdfium.util.Size by class
# name, and the AAR ships no rules to keep those names.
-keep class com.shockwave.pdfium.** { *; }

# PdfBox decodes JPEG 2000 only through an optional library Glosa does not bundle; the converter
# reads text and leaves images to the platform renderer.
-dontwarn com.gemalto.jp2.JP2Decoder
