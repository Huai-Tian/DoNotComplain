package io.github.huai_tian.donotcomplain

import android.util.Log
import io.github.huai_tian.donotcomplain.hook.SettingsHooker
import io.github.huai_tian.donotcomplain.hook.SystemHooker
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * 模块入口。作用域固定为 system（scope.list）——SettingsProvider 声明
 * android:process="system"，运行在 system_server 内，无需独立 scope 条目。
 * 无 UI、无配置：LSPosed 的启用开关即唯一开关（对齐 DisableFlagSecure）。
 */
class DoNotComplainEntry : XposedModule() {

    /**
     * 热重载跨代状态：system_server 的 ClassLoader + 已捕获的 SettingsProvider
     * Class（均为宿主对象，可安全传递；provider 不会二次 attach，需显式复用）
     */
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
        // 宿主对象（ClassLoader / provider Class），非模块类加载器产物，可安全传递
        param.setSavedInstanceState(
            listOfNotNull(systemClassLoader, SettingsHooker.providerClass)
        )
        return true
    }

    @Suppress("UNCHECKED_CAST")
    override fun onHotReloaded(param: HotReloadedParam) {
        val saved = param.savedInstanceState as? List<Any?>
        val classLoader = saved?.firstOrNull() as? ClassLoader
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
        runCatching { SettingsHooker.install(this, classLoader) }
            .onFailure { log(Log.ERROR, TAG, "Failed to install settings hooks", it) }
    }

    private companion object {
        const val TAG = "DoNotComplain"
    }
}