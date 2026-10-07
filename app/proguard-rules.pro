# R8 activo en release (isMinifyEnabled = true). Reglas deliberadamente conservadoras:
# priorizan no romper nada por sobre exprimir el shrink al máximo.

# libsu (shell root): la mayoría de sus llamadas son directas, no por reflection, pero corre
# comandos nativos/JNI en algunos paths internos — lo dejamos entero por las dudas, aunque
# el AAR ya trae sus propias consumer-rules.
-keep class com.topjohnwu.superuser.** { *; }
-dontwarn com.topjohnwu.superuser.**

# Nuestro propio código: nada acá se llama por nombre/reflection (ni Gson ni
# kotlinx.serialization en el proyecto), pero como es la primera vez que corre con R8 activo,
# lo dejamos sin achicar/ofuscar por ahora — igual se benefician las otras optimizaciones de
# R8 (inlining, eliminación de código muerto real). Se puede ajustar más adelante una vez
# confirmado un build real sin sorpresas.
-keep class com.sdcardbind.manager.** { *; }

-dontwarn kotlinx.coroutines.**
