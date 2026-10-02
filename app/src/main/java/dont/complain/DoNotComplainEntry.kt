package dont.complain

import android.util.Log
import dont.complain.hook.SystemHooker
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * 模块入口。作用域固定为 system（scope.list），只在 system_server 中被加载。
 * 无 UI、无配置：LSPosed 的启用开关即唯一开关（对齐 DisableFlagSecure）。
 */
class DoNotComplainEntry : XposedModule() {

    /** system_server 的 ClassLoader，热重载后经 savedInstanceState 传递复用 */
    private var systemClassLoader: ClassLoader? = null

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        super.onSystemServerStarting(param)
        log(
            Log.INFO, TAG,
            "Loading in system_server (framework=$frameworkName $frameworkVersion, api=$apiVersion)"
        )
        systemClassLoader = param.classLoader
        installHooks(param.classLoader)
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        // ClassLoader 是宿主（system_server）对象，非模块类加载器产物，可安全传递
        param.setSavedInstanceState(systemClassLoader)
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        val classLoader = param.savedInstanceState as? ClassLoader
        if (classLoader != null) {
            systemClassLoader = classLoader
            installHooks(classLoader)
        }
        // 同 id 的 hook 已在重装时被原子替换；卸载新版本不再注册的旧 hook
        param.oldHookHandles.forEach { handle ->
            if (!SystemHooker.hookedIds.contains(handle.id)) {
                handle.unhook()
            }
        }
    }

    private fun installHooks(classLoader: ClassLoader) {
        runCatching { SystemHooker.install(this, classLoader) }
            .onFailure { log(Log.ERROR, TAG, "Failed to install hooks", it) }
    }

    private companion object {
        const val TAG = "DoNotComplain"
    }
}
