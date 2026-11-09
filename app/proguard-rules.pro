# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in android-sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

-dontobfuscate
#-renamesourcefileattribute SourceFile
#-keepattributes SourceFile,LineNumberTable

# Yummy's durable Sora 0.24.4 undo-history codec reflects over the pinned
# UndoManager/action-stack implementation. Keep those members stable in release builds.
-keep class io.github.rosemoe.sora.text.UndoManager { *; }
-keep class io.github.rosemoe.sora.text.UndoManager$* { *; }
