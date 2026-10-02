package dont.complain.hook

import android.app.NotificationChannel
import android.content.SharedPreferences
import android.os.Binder
import android.os.Build
import android.os.Parcel
import android.util.Log
import dont.complain.Config
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * system_server 内的通知权限查询欺骗。
 *
 * 设计原则（与 AOSP 8.1 / 13 源码核对）：
 * - 只 hook “查询类”方法，绝不触碰投递裁决路径
 *   （投递走 NMS 内部 importance / PermissionHelper，且查询前都会 clearCallingIdentity）；
 * - 仅当调用方（binder calling uid）查询的是自身状态时才说谎，
 *   系统 / Settings / SystemUI 查询其他应用时始终返回真实结果；
 * - 所有反射查找相互独立，找不到即跳过，单个 hook 点失败不影响其余。
 */
object SystemHooker {

    private const val TAG = "DoNotComplain"

    /** android.app.AppOpsManager#OP_POST_NOTIFICATION，API 19 起恒为 11 */
    private const val OP_POST_NOTIFICATION = 11

    /** AppOpsManager#MODE_ALLOWED == PackageManager#PERMISSION_GRANTED */
    private const val ALLOWED = 0

    /** NotificationManager#IMPORTANCE_NONE / IMPORTANCE_DEFAULT */
    private const val IMPORTANCE_NONE = 0
    private const val IMPORTANCE_DEFAULT = 3

    private const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

    private const val NMS_CLASS = "com.android.server.notification.NotificationManagerService"

    private val APP_OPS_CLASS_CANDIDATES = listOf(
        "com.android.server.appop.AppOpsService", // Android 11+
        "android.app.AppOpsService",              // Android 9/10
        "com.android.server.AppOpsService",       // Android 8.x
    )

    private val PERMISSION_SERVICE_CLASS_CANDIDATES = listOf(
        "android.permission.PermissionManagerService",               // Android 13+
        "com.android.server.pm.permission.PermissionManagerService", // 委托链上的旧类名
    )

    private var prefs: SharedPreferences? = null

    private val uidPackages = ConcurrentHashMap<Int, Set<String>>()

    fun install(xposed: XposedInterface, classLoader: ClassLoader) {
        prefs = runCatching { xposed.getRemotePreferences(Config.PREFS_GROUP) }.getOrNull()

        var count = 0
        count += hookNotificationManagerService(xposed, classLoader)
        count += hookAppOpsService(xposed, classLoader)
        if (Build.VERSION.SDK_INT >= 33) {
            count += hookPermissionManagerService(xposed, classLoader)
        }
        xposed.log(Log.INFO, TAG, "Installed $count hooks in system_server")
    }

    private fun enabled() = prefs?.getBoolean(Config.KEY_ENABLED, true) ?: true

    // ------------------------------------------------------------------
    // NotificationManagerService
    // ------------------------------------------------------------------

    private fun hookNotificationManagerService(xposed: XposedInterface, cl: ClassLoader): Int {
        val nms = load(cl, NMS_CLASS, xposed) ?: return 0
        var count = 0
        // binder 实现位于 NMS 的内部类（如 NotificationService），需要连嵌套类一起遍历
        for (clazz in collectSelfAndNested(nms, depth = 2)) {
            for (method in clazz.declaredMethods) {
                if (Modifier.isAbstract(method.modifiers)) continue
                when (method.name) {
                    "areNotificationsEnabledForPackage",
                    "areNotificationsEnabledForChannel",
                    "areChannelsEnabled" -> {
                        // (String pkg, int uid[, String channelId]) -> boolean，
                        // binder 方法签名由 AIDL 固定，跨 OEM 稳定
                        if (method.parameterCount in 2..3
                            && method.returnType == java.lang.Boolean.TYPE
                        ) {
                            if (hookSafely(xposed, method, nmsBoolHooker)) count++
                        }
                    }
                    "getNotificationChannel",
                    "getNotificationChannels" -> {
                        if (method.parameterCount in 1..5) {
                            if (hookSafely(xposed, method, channelHooker)) count++
                        }
                    }
                }
            }
        }
        xposed.log(Log.INFO, TAG, "NotificationManagerService: $count hooks")
        return count
    }

    /** 应用查询自身总开关 / 渠道开关时返回 true */
    private val nmsBoolHooker: Hooker = Hooker { chain ->
        if (!enabled()) {
            chain.proceed()
        } else {
            val uidArg = chain.args.getOrNull(1) as? Int
            if (uidArg != null && Binder.getCallingUid() == uidArg) {
                true
            } else {
                chain.proceed()
            }
        }
    }

    // ------------------------------------------------------------------
    // AppOpsService
    // ------------------------------------------------------------------

    private fun hookAppOpsService(xposed: XposedInterface, cl: ClassLoader): Int {
        for (name in APP_OPS_CLASS_CANDIDATES) {
            val clazz = load(cl, name, xposed) ?: continue
            var count = 0
            for (method in clazz.declaredMethods) {
                // checkOperation(int code, int uid, String packageName) -> int
                if (method.name == "checkOperation" && method.parameterCount == 3
                    && method.parameterTypes[0] == Integer.TYPE
                    && method.parameterTypes[1] == Integer.TYPE
                    && method.returnType == Integer.TYPE
                ) {
                    if (hookSafely(xposed, method, appOpsHooker)) count++
                }
            }
            if (count > 0) {
                xposed.log(Log.INFO, TAG, "$name: $count hooks")
                return count
            }
        }
        xposed.log(Log.WARN, TAG, "AppOpsService#checkOperation not found")
        return 0
    }

    /**
     * 应用查询自身 OP_POST_NOTIFICATION 时返回 MODE_ALLOWED。
     *
     * 注意：13 之前 NMS 投递通知时会以应用身份内部调用 checkOpNoThrow，
     * 该类调用的调用栈中必然出现 com.android.server.* 帧——遇到时放行真实
     * 结果，保证“查询被欺骗、投递不受影响”。
     */
    private val appOpsHooker: Hooker = Hooker { chain ->
        if (!enabled()) {
            chain.proceed()
        } else {
            val args = chain.args
            val code = args.getOrNull(0) as? Int
            val uid = args.getOrNull(1) as? Int
            if (code == OP_POST_NOTIFICATION && uid != null
                && Binder.getCallingUid() == uid && !isSystemInternalCall(chain)
            ) {
                ALLOWED
            } else {
                chain.proceed()
            }
        }
    }

    // ------------------------------------------------------------------
    // PermissionManagerService（Android 13+，POST_NOTIFICATIONS 运行时权限）
    // ------------------------------------------------------------------

    private fun hookPermissionManagerService(xposed: XposedInterface, cl: ClassLoader): Int {
        var total = 0
        for (name in PERMISSION_SERVICE_CLASS_CANDIDATES) {
            val clazz = load(cl, name, xposed) ?: continue
            var count = 0
            for (method in clazz.declaredMethods) {
                if (Modifier.isAbstract(method.modifiers)) continue
                when {
                    // checkPermission(String permName, String pkgName, int userId) -> int
                    method.name == "checkPermission" && method.parameterCount == 3
                            && method.parameterTypes[0] == String::class.java
                            && method.returnType == Integer.TYPE -> {
                        if (hookSafely(xposed, method, permPkgHooker)) count++
                    }
                    // checkUidPermission(String permName, int uid, int userId) -> int
                    method.name == "checkUidPermission" && method.parameterCount == 3
                            && method.parameterTypes[0] == String::class.java
                            && method.returnType == Integer.TYPE -> {
                        if (hookSafely(xposed, method, permUidHooker)) count++
                    }
                }
            }
            xposed.log(Log.INFO, TAG, "$name: $count hooks")
            total += count
        }
        return total
    }

    private val permPkgHooker: Hooker = Hooker { chain ->
        if (!enabled()) {
            chain.proceed()
        } else {
            val args = chain.args
            val perm = args.getOrNull(0) as? String
            val pkg = args.getOrNull(1) as? String
            if (perm == POST_NOTIFICATIONS && pkg != null && isSelfPackage(pkg)) {
                ALLOWED
            } else {
                chain.proceed()
            }
        }
    }

    private val permUidHooker: Hooker = Hooker { chain ->
        if (!enabled()) {
            chain.proceed()
        } else {
            val args = chain.args
            val perm = args.getOrNull(0) as? String
            val uid = args.getOrNull(1) as? Int
            if (perm == POST_NOTIFICATIONS && uid != null && Binder.getCallingUid() == uid) {
                ALLOWED
            } else {
                chain.proceed()
            }
        }
    }

    // ------------------------------------------------------------------
    // 通知渠道重要性
    // ------------------------------------------------------------------

    /**
     * getNotificationChannel(s)：应用查询自身渠道时，把返回结果中被用户关闭
     * （IMPORTANCE_NONE）的渠道改写为 IMPORTANCE_DEFAULT。
     *
     * 返回值可能是 NMS 内部的存储对象——必须克隆后修改再返回，否则会真实
     * 改动系统状态；列表场景只替换列表元素（列表本身是临时响应容器）。
     */
    private val channelHooker: Hooker = Hooker { chain ->
        val result = chain.proceed()
        if (enabled() && isSelfChannelQuery(chain)) rewriteChannels(result) else result
    }

    private fun isSelfChannelQuery(chain: Chain): Boolean {
        val method = chain.executable
        val params = method.parameterTypes
        val stringPositions = params.indices.filter { params[it] == String::class.java }
        if (stringPositions.isEmpty()) return false
        // getNotificationChannel 的最后一个 String 参数是 channelId，不参与判断
        val checked = if (method.name == "getNotificationChannel") {
            stringPositions.dropLast(1)
        } else {
            stringPositions
        }
        if (checked.isEmpty()) return false
        val pkgs = packagesForUid(Binder.getCallingUid())
        return checked.all { idx ->
            val pkgArg = chain.args[idx] as? String
            pkgArg != null && pkgArg in pkgs
        }
    }

    private fun rewriteChannels(result: Any?): Any? {
        when (result) {
            is NotificationChannel -> return rewriteChannel(result)
            is List<*> -> rewriteChannelList(result)
            else -> if (result != null) rewriteParceledListSlice(result)
        }
        return result
    }

    @Suppress("UNCHECKED_CAST")
    private fun rewriteChannelList(list: List<*>) {
        for (i in list.indices) {
            val item = list[i]
            if (item is NotificationChannel) {
                val rewritten = rewriteChannel(item)
                if (rewritten !== item && list is MutableList<*>) {
                    runCatching { (list as MutableList<Any?>)[i] = rewritten }
                }
            }
        }
    }

    private fun rewriteParceledListSlice(slice: Any) {
        runCatching {
            val list = slice.javaClass.getMethod("getList").invoke(slice) as? List<*> ?: return
            rewriteChannelList(list)
        }
    }

    private fun rewriteChannel(channel: NotificationChannel): NotificationChannel {
        if (channel.importance != IMPORTANCE_NONE) return channel
        val copy = cloneChannel(channel) ?: return channel
        copy.importance = IMPORTANCE_DEFAULT
        return copy
    }

    private fun cloneChannel(channel: NotificationChannel): NotificationChannel? = runCatching {
        val parcel = Parcel.obtain()
        try {
            channel.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            NotificationChannel.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }.getOrNull()

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun isSystemInternalCall(chain: Chain): Boolean {
        val self = chain.executable.declaringClass.name
        for (element in Thread.currentThread().stackTrace) {
            val cn = element.className
            if (cn == self || cn.startsWith("$self$")) continue
            if (cn.startsWith("com.android.server.")) return true
        }
        return false
    }

    private fun isSelfPackage(pkg: String): Boolean =
        packagesForUid(Binder.getCallingUid()).contains(pkg)

    private fun packagesForUid(uid: Int): Set<String> =
        uidPackages.computeIfAbsent(uid) { lookupPackagesForUid(it) }

    private fun lookupPackagesForUid(uid: Int): Set<String> = runCatching {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val binder = serviceManager.getMethod("getService", String::class.java)
            .invoke(null, "package") as? android.os.IBinder ?: return emptySet()
        val stub = Class.forName("android.content.pm.IPackageManager\$Stub")
        val pm = stub.getDeclaredMethod("asInterface", android.os.IBinder::class.java)
            .invoke(null, binder)
        val packages = pm.javaClass
            .getMethod("getPackagesForUid", Integer.TYPE)
            .invoke(pm, uid) as? Array<*> ?: return emptySet()
        packages.filterIsInstance<String>().toSet()
    }.getOrDefault(emptySet())

    private fun load(cl: ClassLoader, name: String, xposed: XposedInterface): Class<*>? =
        runCatching { cl.loadClass(name) }.getOrNull().also {
            if (it == null) xposed.log(Log.WARN, TAG, "Class not found: $name")
        }

    private fun hookSafely(xposed: XposedInterface, method: Method, hooker: Hooker): Boolean =
        runCatching {
            xposed.hook(method).intercept(hooker)
            true
        }.getOrElse {
            xposed.log(Log.WARN, TAG, "Failed to hook ${method.declaringClass.name}#${method.name}", it)
            false
        }

    private fun collectSelfAndNested(root: Class<*>, depth: Int): List<Class<*>> {
        val result = ArrayList<Class<*>>()
        result.add(root)
        if (depth > 0) {
            for (nested in root.declaredClasses) {
                result.addAll(collectSelfAndNested(nested, depth - 1))
            }
        }
        return result
    }
}
