# Native bridge entry points
-keep class com.quantumobile.launcher.game.NativeBridge { *; }
-keep class org.lwjgl.glfw.CallbackBridge { *; }
-keep class net.kdt.pojavlaunch.utils.JREUtils { *; }
-keep class com.oracle.dalvik.VMLauncher { *; }
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
