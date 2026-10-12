# Native bridge entry points
-keep class com.quantumobile.launcher.game.NativeBridge { *; }
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
