package io.github.huai_tian.donotcomplain.hook

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ProviderInfo
import android.os.Binder
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.reflect.Modifier

/**
 * Settings 查询观察 hook（Layer F，DuckUSB 同款架构）。
 *
 * 关键事实（上版架构错误的根因）：SettingsProvider 声明 android:process="system"，
 * **没有独立进程**——它作为 ContentProvider attach 进 system_server 运行。
 * 因此 scope 无法也无需包含 com.android.providers.settings（按包勾选会被 LSPosed
 * 回滚：该包名不映射任何独立进程）；模块也永远收不到该包的 onPackageReady。
 *
 * 正确姿势（DuckUSB 判例）：在 system_server 里 hook
 * ContentProvider.attachInfo（framework 类，system_server 主 classloader 可见；
 * SettingsProvider 的类由独立 PathClassLoader 加载，无法直接 loadClass），
 * 等 authority=="settings" 的 provider attach 时截获其真实 Class，
 * 再 hook 其 call（查询 getter 走 call）与 query（直接读表的检测路径）。
 *
 * 本层为**观察模式**（长期取证基础设施，非开发探针）：记录 uid ≥ 10000 的
 * 调用方读了什么键（settings-obs 日志行），不改任何值。抖音战役终局已证明
 * 其判定路径不经 Settings（readSecureString 是 FeatureSwitchReport 上报数据），
 * 保留意义：未来任何应用的 Settings 侧检测取证（新 ROM 自定义动词、新键名）。
 * 性能护栏：String 参数位缓存、uid→包名缓存、观察上限后整层静默。
 */
object SettingsHooker {

    private const val TAG = "DoNotComplain"
    private const val SETTINGS_AUTHORITY = "settings"
    private const val SETTINGS_PROVIDER_CLASS = "com.android.providers.settings.SettingsProvider"
    private const val FIRST_APP_UID = 10000
    private const val OBS_LIMIT = 500

    /** 已捕获的 SettingsProvider Class（热重载后经 saved state 复用） */
    @Volatile
    var providerClass: Class<*>? = null
        private set

    private var xposedRef: XposedInterface? = null
    private var providerHooked = false

    /**
     * 安装等待器：hook attachInfo，等 SettingsProvider attach 进 system_server。
     * 时序：onSystemServerStarting 早于所有 provider attach，必然截得到。
     */
    fun install(xposed: XposedInterface, cl: ClassLoader): Int {
        xposedRef = xposed
        val cp = runCatching { cl.loadClass("android.content.ContentProvider") }.getOrNull()
        val attachInfo = runCatching {
            cp?.getDeclaredMethod(
                "attachInfo", Context::class.java, ProviderInfo::class.java
            )
        }.getOrNull()
        if (attachInfo == null) {
            xposed.log(Log.ERROR, TAG, "ContentProvider.attachInfo not found; Layer F disabled")
            return 0
        }
        hookSafely(xposed, attachInfo, attachInfoHooker)
        xposed.log(Log.INFO, TAG, "Layer Settings(obs): armed (awaiting SettingsProvider attach)")
        // 热重载场景：provider 可能早已 attach（不会再次触发 attachInfo）
        providerClass?.let { hookProviderCall(xposed, it) }
        return 1
    }

    /** attachInfo 拦截：识别 settings provider 并转挂其 call/query */
    private val attachInfoHooker: Hooker = Hooker { chain ->
        val result = chain.proceed()
        runCatching {
            val provider = chain.thisObject ?: return@Hooker result
            val info = chain.args.getOrNull(1) as? ProviderInfo
            // 按权威匹配（OEM 可能改类名）；authority 可为 ";" 分隔列表
            val isSettings = info?.authority?.split(";")
                ?.any { it.trim() == SETTINGS_AUTHORITY } == true
                    || provider.javaClass.name == SETTINGS_PROVIDER_CLASS
            if (isSettings) {
                providerClass = provider.javaClass
                xposedRef?.let { hookProviderCall(it, provider.javaClass) }
            }
        }
        result
    }

    /** 挂 SettingsProvider.call / query（一次性）；记录精确签名（区分 call/query 覆盖） */
    @Synchronized
    private fun hookProviderCall(xposed: XposedInterface, clazz: Class<*>) {
        if (providerHooked) return
        var count = 0
        val signatures = StringBuilder()
        for (m in clazz.declaredMethods) {
            if (Modifier.isAbstract(m.modifiers)) continue
            if (m.name == "call" && m.parameterCount >= 3) {
                if (hookSafely(xposed, m, callObserver)) {
                    count++
                    signatures.append("\n  call: ${m.toGenericString()}")
                }
            } else if (m.name == "query" && m.parameterCount >= 3) {
                if (hookSafely(xposed, m, queryObserver)) {
                    count++
                    signatures.append("\n  query: ${m.toGenericString()}")
                }
            }
        }
        if (count > 0) {
            providerHooked = true
            xposed.log(Log.INFO, TAG, "Layer Settings(obs): $count hooks on ${clazz.name}$signatures")
        }
    }

    /**
     * call 观察：识别 call(callingPkg/attributionSource, method, name, ...)，
     * method 形如 GET_secure / GET_global。GET_* 记录键名；**非 GET 动词也
     * 一次性记录**（发现 ColorOS 自定义 call 动词，如 OPLUS_GET_*——若存在，
     * GET_ 过滤会漏掉它们）。只记 uid ≥ 10000 的调用方。
     * 性能（交付清理）：String 参数位按方法缓存（免每次反射扫参数表）；
     * 观察上限达到后整层早退（免无谓的包名解析）。
     */
    private val strPosCache = java.util.concurrent.ConcurrentHashMap<java.lang.reflect.Method, IntArray>()

    private val callObserver: Hooker = Hooker { chain ->
        runCatching {
            val uid = Binder.getCallingUid()
            if (uid >= FIRST_APP_UID) {
                val method = chain.executable as? java.lang.reflect.Method ?: return@runCatching
                val strPos = strPosCache.getOrPut(method) {
                    val params = method.parameterTypes
                    params.indices.filter { params[it] == String::class.java }.toIntArray()
                }
                if (strPos.size >= 2) {
                    val methodArg = chain.args.getOrNull(strPos[0]) as? String
                    val nameArg = chain.args.getOrNull(strPos[1]) as? String
                    if (methodArg != null && nameArg != null) {
                        if (methodArg.startsWith("GET_")) {
                            observe(uid, "$methodArg $nameArg")
                        } else if (!methodArg.startsWith("PUT_")) {
                            // OEM 自定义动词发现（GET/PUT 之外的都是可疑目标）
                            observe(uid, "verb $methodArg")
                        }
                    }
                }
            }
        }
        chain.proceed()
    }

    /** query 观察：记录 uri（直接读表的检测路径） */
    private val queryObserver: Hooker = Hooker { chain ->
        runCatching {
            val uid = Binder.getCallingUid()
            if (uid >= FIRST_APP_UID) {
                val uri = chain.args.filterIsInstance<android.net.Uri>().firstOrNull()
                if (uri != null) observe(uid, "query ${uri.path}")
            }
        }
        chain.proceed()
    }

    private val observed = java.util.Collections.synchronizedSet(HashSet<String>())

    /** uid → 包名缓存（观察热路径免重复 binder 解析；上限后整层静默） */
    private val uidNames = java.util.concurrent.ConcurrentHashMap<Int, String>()

    private fun observe(uid: Int, what: String) {
        if (observed.size >= OBS_LIMIT) return
        val caller = uidNames.getOrPut(uid) { callerPackage(uid) }
        val key = "$caller|$what"
        if (observed.add(key)) {
            runCatching { xposedRef?.log(Log.INFO, TAG, "settings-obs: caller=$caller $what") }
        }
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun callerPackage(uid: Int): String = runCatching {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val binder = serviceManager.getMethod("getService", String::class.java)
            .invoke(null, "package") as? android.os.IBinder ?: return "uid:$uid"
        val stub = Class.forName($$"android.content.pm.IPackageManager$Stub")
        val pm = stub.getDeclaredMethod("asInterface", android.os.IBinder::class.java)
            .invoke(null, binder)
        val packages = pm.javaClass
            .getMethod("getPackagesForUid", Integer.TYPE)
            .invoke(pm, uid) as? Array<*> ?: return "uid:$uid"
        packages.filterIsInstance<String>().firstOrNull() ?: "uid:$uid"
    }.getOrDefault("uid:$uid")

    private fun hookSafely(xposed: XposedInterface, method: java.lang.reflect.Method, hooker: Hooker): Boolean =
        runCatching {
            val id = method.toGenericString()
            // 复用 SystemHooker 的 id 集合：Entry 的热重载卸载逻辑据此判断
            SystemHooker.hookedIds.add(id)
            xposed.hook(method).setId(id).intercept(hooker)
            true
        }.getOrElse {
            xposed.log(Log.WARN, TAG, "Failed to hook ${method.declaringClass.name}#${method.name}", it)
            false
        }
}