package com.sdcardbind.manager

import android.app.Application
import com.topjohnwu.superuser.Shell

/**
 * Antes, tanto la configuración del builder de libsu como la primera adquisición real del
 * shell root vivían del lado de `MainActivity` (la config en un `init` de su companion, la
 * adquisición recién en un `LaunchedEffect` de la primera composición). Eso ataba el "despertar"
 * de KernelSU (autenticar y levantar su daemon, algo notablemente más lento la primera vez tras
 * un reinicio que en usos posteriores con todo cacheado) al mismo momento en que la UI está
 * componiendo su primer frame — de ahí los tirones iniciales.
 *
 * Acá se configura el builder y se dispara la adquisición del shell (`Shell.getShell` con
 * callback, no bloqueante) apenas arranca el proceso, mucho antes de que exista cualquier
 * Activity. Así esa espera queda corriendo en paralelo con el resto del arranque en frío del
 * proceso en vez de sumarse recién sobre la primera composición: para cuando la UI la pida
 * (`RootOps.isRootAvailable()` en `MainActivity`), o ya está lista, o le queda bastante menos
 * camino por recorrer.
 */
class BindApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER or Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(20)
        )
        // Callback vacío a propósito: acá solo interesa adelantar el trabajo. El resultado
        // real (rootOk) lo sigue pidiendo la UI normalmente vía RootOps.isRootAvailable(),
        // que va a encontrar el shell ya listo (o bastante más cerca de estarlo).
        Shell.getShell { }
    }
}
