package dont.complain

import android.util.Log
import dont.complain.hook.SystemHooker
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * 模块入口。作用域固定为 system（scope.list），只在 system_server 中被加载。
 */
class DoNotComplainEntry : XposedModule() {

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        super.onSystemServerStarting(param)
        log(
            Log.INFO, TAG,
            "Loading in system_server (framework=$frameworkName $frameworkVersion, api=$apiVersion)"
        )
        runCatching { SystemHooker.install(this, param.classLoader) }
            .onFailure { log(Log.ERROR, TAG, "Failed to install hooks", it) }
    }

    private companion object {
        const val TAG = "DoNotComplain"
    }
}
