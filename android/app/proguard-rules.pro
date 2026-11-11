# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class app.pixlink.** { *** Companion; }
-keepclasseswithmembers class app.pixlink.** { kotlinx.serialization.KSerializer serializer(...); }

# Shizuku user service is instantiated by class name
-keep class app.pixlink.power.ShellService { *; }
